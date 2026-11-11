/**
 * VoicePulse — Release Lifecycle & Branch Enforcement Utilities
 *
 * Implements core domain logic for GitFlow release management, branch target
 * validation, semantic versioning calculations, and changelog synchronization.
 */

/**
 * Validates PR head and base branch names against VoicePulse GitFlow invariants.
 *
 * Routing Rules:
 *  - release/*  -> main ONLY
 *  - hotfix/*   -> main ONLY
 *  - feature/*  -> develop ONLY
 *  - bugfix/*   -> develop ONLY
 *  - refactor/* -> develop ONLY
 *  - internal/* -> develop ONLY
 *  - docs/*     -> develop ONLY
 *  - sync/main-to-develop-* -> develop ONLY
 *  - dependabot/*, copilot/* -> permitted for develop or main
 *
 * @param {string} headRef
 * @param {string} baseRef
 * @returns {{valid: boolean, error?: string, labels: string[]}}
 */
function validateBranchTarget(headRef, baseRef) {
  if (!headRef || !baseRef) {
    return {
      valid: false,
      error: 'Both headRef and baseRef must be non-empty strings.',
      labels: []
    };
  }

  // 1. Whitelisted system branches (e.g. dependabot, copilot)
  if (headRef.startsWith('dependabot/') || headRef.startsWith('copilot/')) {
    return {
      valid: true,
      labels: ['dependencies']
    };
  }

  // 2. Automated Post-Release Sync branches: sync/main-to-develop-<sha>
  if (/^sync\/main-to-develop-[a-f0-9]+$/i.test(headRef)) {
    if (baseRef !== 'develop') {
      return {
        valid: false,
        error: `Sync branch '${headRef}' MUST target 'develop', but targets '${baseRef}'.`,
        labels: ['sync']
      };
    }
    return {
      valid: true,
      labels: ['sync']
    };
  }

  // 3. Match canonical branch prefixes: prefix/description
  const match = headRef.match(/^([a-z0-9_-]+)\/(.+)$/);
  if (!match) {
    return {
      valid: false,
      error: `Branch '${headRef}' does not conform to VoicePulse naming conventions. Allowed prefixes: feature/, bugfix/, hotfix/, release/, refactor/, internal/, docs/, or sync/main-to-develop-<sha>.`,
      labels: []
    };
  }

  const prefix = match[1];

  switch (prefix) {
    case 'release':
      if (baseRef !== 'main') {
        return {
          valid: false,
          error: `Release branch '${headRef}' MUST target 'main', but targets '${baseRef}'.`,
          labels: ['release']
        };
      }
      return {
        valid: true,
        labels: ['release']
      };

    case 'hotfix':
      if (baseRef !== 'main') {
        return {
          valid: false,
          error: `Hotfix branch '${headRef}' MUST target 'main', but targets '${baseRef}'.`,
          labels: ['hotfix']
        };
      }
      return {
        valid: true,
        labels: ['hotfix']
      };

    case 'feature':
      if (baseRef !== 'develop') {
        return {
          valid: false,
          error: `Feature branch '${headRef}' MUST target 'develop', but targets '${baseRef}'.`,
          labels: ['enhancement']
        };
      }
      return {
        valid: true,
        labels: ['enhancement']
      };

    case 'bugfix':
      if (baseRef !== 'develop') {
        return {
          valid: false,
          error: `Bugfix branch '${headRef}' MUST target 'develop', but targets '${baseRef}'.`,
          labels: ['bug']
        };
      }
      return {
        valid: true,
        labels: ['bug']
      };

    case 'refactor':
      if (baseRef !== 'develop') {
        return {
          valid: false,
          error: `Refactor branch '${headRef}' MUST target 'develop', but targets '${baseRef}'.`,
          labels: ['enhancement']
        };
      }
      return {
        valid: true,
        labels: ['enhancement']
      };

    case 'internal':
      if (baseRef !== 'develop') {
        return {
          valid: false,
          error: `Internal branch '${headRef}' MUST target 'develop', but targets '${baseRef}'.`,
          labels: ['tooling']
        };
      }
      return {
        valid: true,
        labels: ['tooling']
      };

    case 'docs':
      if (baseRef !== 'develop') {
        return {
          valid: false,
          error: `Documentation branch '${headRef}' MUST target 'develop', but targets '${baseRef}'.`,
          labels: ['documentation']
        };
      }
      return {
        valid: true,
        labels: ['documentation']
      };

    default:
      return {
        valid: false,
        error: `Branch prefix '${prefix}/' is not recognized. Disallowed prefixes like 'feat/' and 'fix/' must use 'feature/' or 'bugfix/'.`,
        labels: []
      };
  }
}

/**
 * Calculates the next semantic version and -SNAPSHOT iteration string.
 *
 * @param {string} currentVersion e.g. "0.1.0" or "0.1.0-SNAPSHOT"
 * @param {object} [options]
 * @param {boolean} [options.isMajor=false]
 * @param {boolean} [options.isHotfix=false]
 * @param {boolean} [options.isPatch=false]
 * @returns {{releaseVersion: string, snapshotVersion: string}}
 */
function computeNextVersion(currentVersion, { isMajor = false, isHotfix = false, isPatch = false } = {}) {
  const clean = currentVersion.replace(/-SNAPSHOT$/i, '').trim();
  const parts = clean.split('.').map(n => parseInt(n, 10));

  if (parts.length !== 3 || parts.some(n => isNaN(n))) {
    throw new Error(`Invalid semver version: '${currentVersion}'. Expected X.Y.Z format.`);
  }

  let [major, minor, patch] = parts;

  if (isMajor) {
    major += 1;
    minor = 0;
    patch = 0;
  } else if (isHotfix || isPatch) {
    patch += 1;
  } else {
    // Standard minor bump for planned development cycles
    minor += 1;
    patch = 0;
  }

  const releaseVersion = `${major}.${minor}.${patch}`;
  const snapshotVersion = `${releaseVersion}-SNAPSHOT`;

  return {
    releaseVersion,
    snapshotVersion
  };
}

/**
 * Replaces the ## [Unreleased] header in CHANGELOG.md with the stamped release header.
 * If the unreleased section has no bullet items, injects a fallback maintenance entry
 * to prevent blank release sections.
 *
 * @param {string} changelogContent
 * @param {string} version e.g. "0.1.0"
 * @param {string} dateStr e.g. "2026-11-08"
 * @returns {{modified: boolean, content: string}}
 */
function stampChangelogRelease(changelogContent, version, dateStr) {
  const unreleasedRegex = /^(##\s+\[Unreleased\]\s*)$/m;
  const match = changelogContent.match(unreleasedRegex);
  if (!match) {
    return {
      modified: false,
      content: changelogContent
    };
  }

  const stampedHeader = `## [${version}] - ${dateStr}`;
  const postUnreleased = changelogContent.slice(match.index + match[0].length);

  // Stop at next ## header or EOF
  const nextHeaderMatch = postUnreleased.match(/\n##\s+\[/);
  const unreleasedBody = nextHeaderMatch
    ? postUnreleased.slice(0, nextHeaderMatch.index)
    : postUnreleased;

  const hasBullets = /^\s*-\s+/m.test(unreleasedBody);

  if (!hasBullets) {
    const pre = changelogContent.slice(0, match.index);
    const remainingContent = nextHeaderMatch
      ? postUnreleased.slice(nextHeaderMatch.index)
      : '';
    const updatedContent = `${pre}${stampedHeader}\n\n- Maintenance release with no user-facing changes.\n${remainingContent}`;
    return {
      modified: true,
      content: updatedContent
    };
  }

  const updatedContent = changelogContent.replace(unreleasedRegex, stampedHeader);
  return {
    modified: true,
    content: updatedContent
  };
}

/**
 * Initializes a new ## [Unreleased] section on top of existing released versions in CHANGELOG.md.
 * Idempotent: does nothing if ## [Unreleased] already exists.
 *
 * @param {string} changelogContent
 * @returns {{modified: boolean, content: string}}
 */
function initializeUnreleasedChangelog(changelogContent) {
  const unreleasedRegex = /^(##\s+\[Unreleased\]\s*)$/m;
  if (unreleasedRegex.test(changelogContent)) {
    return {
      modified: false,
      content: changelogContent
    };
  }

  const firstReleaseRegex = /^(##\s+\[[\w\d.-]+\]\s*-\s*\d{4}-\d{2}-\d{2}\s*)$/m;
  const match = changelogContent.match(firstReleaseRegex);

  if (match) {
    const insertIdx = match.index;
    const pre = changelogContent.slice(0, insertIdx);
    const post = changelogContent.slice(insertIdx);
    return {
      modified: true,
      content: `${pre.trimEnd()}\n\n## [Unreleased]\n\n${post}`
    };
  }

  // Fallback: append after top changelog header
  const headerRegex = /^(#\s+Changelog[\s\S]*?)(?=\n##\s|\n?$)/;
  const headerMatch = changelogContent.match(headerRegex);
  if (headerMatch) {
    const pre = headerMatch[1].trimEnd();
    const post = changelogContent.slice(headerMatch[0].length).trimStart();
    return {
      modified: true,
      content: `${pre}\n\n## [Unreleased]\n\n${post}`
    };
  }

  return {
    modified: true,
    content: `# Changelog\n\n## [Unreleased]\n\n${changelogContent}`
  };
}

/**
 * Extracts release notes body for a given version from CHANGELOG.md.
 *
 * @param {string} changelogContent
 * @param {string} version e.g. "0.1.0"
 * @returns {string} The extracted markdown body or empty string
 */
function extractReleaseNotes(changelogContent, version) {
  const versionHeaderRegex = new RegExp(`^##\\s+\\[${version.replace(/\./g, '\\.')}\\](?:\\s*-\\s*\\d{4}-\\d{2}-\\d{2})?\\s*$`, 'm');
  const match = changelogContent.match(versionHeaderRegex);
  if (!match) {
    return '';
  }

  const startIndex = match.index + match[0].length;
  const remaining = changelogContent.slice(startIndex);

  // Stop at next ## header or EOF
  const nextHeaderMatch = remaining.match(/\n##\s+\[/);
  const rawBody = nextHeaderMatch ? remaining.slice(0, nextHeaderMatch.index) : remaining;

  return rawBody.trim();
}

/**
 * Calculates branch naming and metadata from an issue's labels and metadata.
 * Supports both an array of label names (labelNames) and/or a single labelName.
 *
 * @param {object} param0
 * @param {string} [param0.labelName]
 * @param {Array<string|{name: string}>} [param0.labelNames]
 * @param {number|string} param0.issueNumber
 * @param {string} param0.rawTitle
 * @param {string} [param0.issueBody]
 * @returns {{shouldCreate: boolean, branchName?: string, baseBranch?: string, isRelease?: boolean, isMajor?: boolean, prTitle?: string, prBody?: string}}
 */
function calculateBranchMetadata({ labelName, labelNames, issueNumber, rawTitle, issueBody = '' }) {
  const rawCollection = [];
  if (Array.isArray(labelNames)) {
    rawCollection.push(...labelNames.map(l => (typeof l === 'string' ? l : (l && l.name ? l.name : ''))));
  }
  if (labelName) {
    rawCollection.push(labelName);
  }
  const normalizedLabels = [...new Set(rawCollection.filter(Boolean))];

  let prefix = '';
  let baseBranch = 'develop';
  let isRelease = false;

  if (normalizedLabels.includes('release')) {
    prefix = 'release/';
    baseBranch = 'develop';
    isRelease = true;
  } else if (normalizedLabels.includes('hotfix')) {
    prefix = 'hotfix/';
    baseBranch = 'main';
  } else if (normalizedLabels.includes('bug')) {
    prefix = 'bugfix/';
    baseBranch = 'develop';
  } else if (normalizedLabels.includes('refactor')) {
    prefix = 'refactor/';
    baseBranch = 'develop';
  } else if (normalizedLabels.includes('internal')) {
    prefix = 'internal/';
    baseBranch = 'develop';
  } else {
    const featureLabel = normalizedLabels.find(l =>
      ['enhancement', 'feature', 'ci-cd', 'tooling', 'architecture', 'ai-workflow', 'documentation'].includes(l)
    );
    if (featureLabel) {
      prefix = 'feature/';
      baseBranch = 'develop';
    } else {
      return { shouldCreate: false };
    }
  }

  // 1. Remove bracketed prefixes (e.g. [VP-08], [EPIC-01])
  let cleaned = (rawTitle || '').replace(/^\[[^\]]+\]\s*/, '');
  // 2. Remove Conventional Commit prefix (e.g. feat(core):, ci:, fix:)
  cleaned = cleaned.replace(/^[a-z]+(\([a-z0-9_-]+\))?:\s*/i, '');
  // 3. Lowercase and replace non-alphanumeric characters with hyphens
  let slug = cleaned.toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '');
  // 4. Truncate slug to 30 characters and trim trailing hyphens
  slug = slug.substring(0, 30).replace(/-+$/, '');

  const branchName = `${prefix}VP-${issueNumber}-${slug}`;
  const isMajor = /\[x\]\s*Major Release/i.test(issueBody || '');
  const cleanTitle = (rawTitle || '').replace(/^\[[^\]]+\]\s*/, '');
  const prTitle = cleanTitle.endsWith(`(#${issueNumber})`)
    ? cleanTitle
    : `${cleanTitle} (#${issueNumber})`;
  const prBody = `Automated release pull request for #${issueNumber}.\n\nCloses #${issueNumber}`;

  return {
    shouldCreate: true,
    branchName,
    baseBranch,
    isRelease,
    isMajor,
    prTitle,
    prBody
  };
}

/**
 * Resolves the effective GitHub token for automated branch/PR provisioning,
 * preferring PAT_TOKEN (or AUTOMATION_TOKEN) and gracefully falling back to GITHUB_TOKEN.
 *
 * @param {object|string} tokenOrOptions
 * @param {string} [fallbackGithubToken]
 * @returns {string}
 */
function resolveAutomationToken(tokenOrOptions, fallbackGithubToken) {
  let patToken = '';
  let automationToken = '';
  let githubToken = '';

  if (tokenOrOptions && typeof tokenOrOptions === 'object') {
    patToken = (tokenOrOptions.patToken || '').trim();
    automationToken = (tokenOrOptions.automationToken || '').trim();
    githubToken = (tokenOrOptions.githubToken || '').trim();
  } else {
    automationToken = (tokenOrOptions || '').trim();
    githubToken = (fallbackGithubToken || '').trim();
  }

  const resolved = patToken || automationToken || githubToken;
  if (!resolved) {
    throw new Error('No valid GitHub token available (PAT_TOKEN, AUTOMATION_TOKEN, or GITHUB_TOKEN required).');
  }
  return resolved;
}

/**
 * Builds deterministic branch, commit, and Pull Request metadata for
 * post-release synchronization from main back into develop.
 * Commit message explicitly omits '[skip ci]' so required pull_request CI checks run.
 *
 * @param {object} params
 * @param {string} params.currentVersion
 * @param {string} params.snapshotVersion
 * @param {string} params.shortSha
 * @returns {{
 *   syncBranch: string,
 *   commitMessage: string,
 *   prTitle: string,
 *   prBody: string,
 *   labels: string[]
 * }}
 */
function buildSyncPrMetadata({ currentVersion, snapshotVersion, shortSha } = {}) {
  const cleanCurrent = (currentVersion || '').trim();
  const cleanSnapshot = (snapshotVersion || '').trim();
  const cleanSha = (shortSha || '').trim();

  if (!cleanCurrent || !cleanSnapshot || !cleanSha) {
    throw new Error('currentVersion, snapshotVersion, and shortSha are required to build sync PR metadata.');
  }

  const syncBranch = `sync/main-to-develop-${cleanSha}`;
  const commitMessage = `chore(sync): bump version to ${cleanSnapshot} and initialize unreleased changelog`;
  const prTitle = `chore(sync): merge main (${cleanCurrent}) into develop (${cleanSnapshot})`;
  const prBody = [
    '## Automated Post-Release Synchronization',
    '',
    'This PR synchronizes production release changes from `main` back into `develop` and bumps the development iteration.',
    '',
    `- **Production Release:** \`${cleanCurrent}\` (commit \`${cleanSha}\`)`,
    `- **Next Iteration:** \`${cleanSnapshot}\``,
    `- **Source Branch:** \`${syncBranch}\``,
    `- **Target Branch:** \`develop\``,
    '',
    '### Merge Instructions',
    'Merge this PR into `develop` using **Create a merge commit** to maintain clean Git history and tag ancestry.'
  ].join('\n');

  return {
    syncBranch,
    commitMessage,
    prTitle,
    prBody,
    labels: ['sync']
  };
}

module.exports = {
  calculateBranchMetadata,
  validateBranchTarget,
  computeNextVersion,
  stampChangelogRelease,
  initializeUnreleasedChangelog,
  extractReleaseNotes,
  resolveAutomationToken,
  buildSyncPrMetadata
};
