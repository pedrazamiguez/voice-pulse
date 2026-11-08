/**
 * VoicePulse — Automated CHANGELOG.md Maintenance Script
 *
 * Implements automated issue recording upon issue closure for Specification-Driven
 * Development (SDD). Adheres strictly to Keep a Changelog 1.1.0 conventions.
 */

const SECTION_ORDER = [
  'Added',
  'Changed',
  'Deprecated',
  'Removed',
  'Fixed',
  'Security',
  'Tooling & Infrastructure',
  'Documentation'
];

/**
 * Strips leading bracket tags (e.g., [VP-08], [EPIC-01]) while retaining
 * Conventional Commit prefixes and descriptions.
 *
 * @param {string} title
 * @returns {string}
 */
function cleanTitle(title) {
  if (!title) return '';
  return title.replace(/^(\[[^\]]+\]\s*)+/, '').trim();
}

/**
 * Maps an issue title and labels to Keep a Changelog section categories.
 * Conventional Commit prefix takes precedence over GitHub labels.
 *
 * @param {string} title
 * @param {Array<string|{name: string}>} labels
 * @returns {string} Target category name
 */
function determineCategory(title, labels = []) {
  const cleaned = cleanTitle(title);
  const normalizedLabels = labels.map(l =>
    (typeof l === 'string' ? l : l && l.name ? l.name : '').toLowerCase()
  );

  // 1. Evaluate Conventional Commit prefix first (Prefix Priority)
  const prefixMatch = cleaned.match(/^([a-z]+)(?:\([a-z0-9_.-]+\))?!?:/i);
  if (prefixMatch) {
    const prefix = prefixMatch[1].toLowerCase();
    switch (prefix) {
      case 'feat':
      case 'feature':
        return 'Added';
      case 'fix':
      case 'bug':
        return 'Fixed';
      case 'refactor':
      case 'perf':
      case 'performance':
        return 'Changed';
      case 'deprecate':
      case 'deprecated':
        return 'Deprecated';
      case 'remove':
      case 'removed':
        return 'Removed';
      case 'sec':
      case 'security':
        return 'Security';
      case 'ci':
      case 'chore':
      case 'tooling':
      case 'infra':
      case 'build':
        return 'Tooling & Infrastructure';
      case 'docs':
      case 'doc':
        return 'Documentation';
      default:
        break;
    }
  }

  // 2. Evaluate fallback labels when prefix is absent or unrecognized
  if (normalizedLabels.includes('bug')) {
    return 'Fixed';
  }
  if (normalizedLabels.includes('enhancement') || normalizedLabels.includes('feature')) {
    return 'Added';
  }
  if (
    normalizedLabels.includes('architecture') ||
    normalizedLabels.includes('refactor') ||
    normalizedLabels.includes('performance')
  ) {
    return 'Changed';
  }
  if (normalizedLabels.includes('security')) {
    return 'Security';
  }
  if (
    normalizedLabels.includes('ci-cd') ||
    normalizedLabels.includes('tooling') ||
    normalizedLabels.includes('infrastructure')
  ) {
    return 'Tooling & Infrastructure';
  }
  if (normalizedLabels.includes('documentation')) {
    return 'Documentation';
  }

  // 3. Default fallback category
  return 'Changed';
}

/**
 * Validates whether an issue should be recorded in CHANGELOG.md.
 * Ignores issues closed as not_planned or labeled with triage skip labels.
 *
 * @param {object} param0
 * @param {string} [param0.stateReason]
 * @param {Array<string|{name: string}>} [param0.labels]
 * @returns {boolean}
 */
function shouldProcessIssue({ stateReason, labels = [] }) {
  if (stateReason && stateReason !== 'completed') {
    return false;
  }
  const normalizedLabels = labels.map(l =>
    (typeof l === 'string' ? l : l && l.name ? l.name : '').toLowerCase()
  );
  const excludedLabels = ['duplicate', 'wontfix', 'invalid'];
  if (normalizedLabels.some(l => excludedLabels.includes(l))) {
    return false;
  }
  return true;
}

/**
 * Parses markdown lines inside the ## [Unreleased] block into discrete category sections.
 *
 * @param {string} unreleasedBody
 * @returns {Array<{name: string|null, lines: Array<string>}>}
 */
function parseSections(unreleasedBody) {
  const lines = unreleasedBody.split('\n');
  const sections = [];
  let currentSection = { name: null, lines: [] };

  for (const line of lines) {
    const match = line.match(/^###\s+(.+)$/);
    if (match) {
      sections.push(currentSection);
      currentSection = { name: match[1].trim(), lines: [] };
    } else {
      currentSection.lines.push(line);
    }
  }
  sections.push(currentSection);
  return sections;
}

/**
 * Formats parsed category sections back into clean markdown.
 *
 * @param {Array<{name: string|null, lines: Array<string>}>} sections
 * @returns {string}
 */
function formatSections(sections) {
  let result = '\n';
  for (const section of sections) {
    if (section.name === null) {
      const text = section.lines.join('\n').trim();
      if (text) {
        result += text + '\n\n';
      }
    } else {
      const items = section.lines
        .map(l => l.trim())
        .filter(l => l.startsWith('- '));
      if (items.length > 0) {
        result += `### ${section.name}\n`;
        for (const item of items) {
          result += `${item}\n`;
        }
        result += '\n';
      }
    }
  }
  return result;
}

/**
 * Updates CHANGELOG.md content with a new completed issue entry.
 * Guarantees strict idempotency and category placement order.
 *
 * @param {string} changelogContent
 * @param {object} param1
 * @param {number|string} param1.issueNumber
 * @param {string} param1.issueTitle
 * @param {string} param1.issueUrl
 * @param {Array<string|{name: string}>} [param1.labels]
 * @returns {{modified: boolean, message: string, content: string, category?: string}}
 */
function updateChangelogContent(changelogContent, { issueNumber, issueTitle, issueUrl, labels = [] }) {
  const idempotencyRegex = new RegExp(`(\\b#${issueNumber}\\b|/issues/${issueNumber}\\b)`);
  if (idempotencyRegex.test(changelogContent)) {
    return {
      modified: false,
      message: `Issue #${issueNumber} is already recorded in CHANGELOG.md. Skipping.`,
      content: changelogContent
    };
  }

  const category = determineCategory(issueTitle, labels);
  const cleaned = cleanTitle(issueTitle);
  const entry = `- ${cleaned} ([#${issueNumber}](${issueUrl}))`;

  // Locate ## [Unreleased]
  const unreleasedRegex = /^(##\s+\[Unreleased\]\s*)$/m;
  const match = changelogContent.match(unreleasedRegex);

  let preUnreleased = '';
  let unreleasedAndBeyond = '';

  if (!match) {
    const changelogHeaderRegex = /^(#\s+Changelog[\s\S]*?)(?=\n##\s|\n?$)/;
    const headerMatch = changelogContent.match(changelogHeaderRegex);
    if (headerMatch) {
      preUnreleased = headerMatch[1].trimEnd() + '\n\n## [Unreleased]\n';
      unreleasedAndBeyond = changelogContent.slice(headerMatch[0].length).trimStart();
    } else {
      preUnreleased = '# Changelog\n\n## [Unreleased]\n';
      unreleasedAndBeyond = changelogContent;
    }
  } else {
    const unreleasedIndex = match.index;
    const headerLength = match[0].length;
    preUnreleased = changelogContent.slice(0, unreleasedIndex + headerLength) + '\n';
    unreleasedAndBeyond = changelogContent.slice(unreleasedIndex + headerLength);
  }

  // Separate ## [Unreleased] body from subsequent release version headers
  const nextReleaseMatch = unreleasedAndBeyond.match(/\n(##\s+\[[\w\d.-]+\][\s\S]*)$/);
  let unreleasedBody = '';
  let postUnreleased = '';

  if (nextReleaseMatch) {
    unreleasedBody = unreleasedAndBeyond.slice(0, nextReleaseMatch.index);
    postUnreleased = nextReleaseMatch[1];
  } else {
    unreleasedBody = unreleasedAndBeyond;
  }

  const sections = parseSections(unreleasedBody);
  const existingSection = sections.find(s => s.name === category);

  if (existingSection) {
    existingSection.lines.push(entry);
  } else {
    const targetIdx = SECTION_ORDER.indexOf(category);
    let insertAfterIdx = 0; // default after name: null section

    for (let i = 0; i < sections.length; i++) {
      const s = sections[i];
      if (s.name !== null) {
        const orderIdx = SECTION_ORDER.indexOf(s.name);
        if (targetIdx !== -1 && (orderIdx === -1 || orderIdx > targetIdx)) {
          break;
        }
        insertAfterIdx = i;
      }
    }

    sections.splice(insertAfterIdx + 1, 0, {
      name: category,
      lines: [entry]
    });
  }

  const formattedUnreleased = formatSections(sections);
  let finalContent = preUnreleased.trimEnd() + '\n' + formattedUnreleased.trimEnd() + '\n';
  if (postUnreleased) {
    finalContent += '\n' + postUnreleased.trim() + '\n';
  }

  return {
    modified: true,
    message: `Appended issue #${issueNumber} under '### ${category}' in CHANGELOG.md.`,
    category,
    content: finalContent
  };
}

/**
 * GitHub Actions entrypoint invoked via actions/github-script.
 *
 * @param {object} param0
 * @param {object} param0.github Octokit REST client
 * @param {object} param0.context GitHub Action context
 * @param {object} param0.core GitHub Action core logging
 */
async function run({ github, context, core }) {
  const issue = context.payload && context.payload.issue;
  if (!issue) {
    core.info('No issue payload found in context. Exiting.');
    return;
  }

  const issueNumber = issue.number;
  const issueTitle = issue.title;
  const issueUrl = issue.html_url;
  const stateReason = issue.state_reason;
  const labels = issue.labels || [];

  if (!shouldProcessIssue({ stateReason, labels })) {
    core.info(
      `Issue #${issueNumber} skipped (state_reason='${stateReason}', labels=${JSON.stringify(
        labels.map(l => (typeof l === 'string' ? l : l.name))
      )}).`
    );
    return;
  }

  const owner = context.repo.owner;
  const repo = context.repo.repo;

  core.info(`Processing completed issue #${issueNumber}: "${issueTitle}"`);

  let fileData;
  try {
    const response = await github.rest.repos.getContent({
      owner,
      repo,
      path: 'CHANGELOG.md',
      ref: 'develop'
    });
    fileData = response.data;
  } catch (error) {
    if (error.status === 404) {
      core.setFailed('CHANGELOG.md not found on develop branch.');
      return;
    }
    throw error;
  }

  const currentContent = Buffer.from(fileData.content, 'base64').toString('utf8');
  const currentSha = fileData.sha;

  const result = updateChangelogContent(currentContent, {
    issueNumber,
    issueTitle,
    issueUrl,
    labels
  });

  if (!result.modified) {
    core.info(result.message);
    return;
  }

  await github.rest.repos.createOrUpdateFileContents({
    owner,
    repo,
    path: 'CHANGELOG.md',
    branch: 'develop',
    message: `docs(changelog): record completion of #${issueNumber} [skip ci]`,
    content: Buffer.from(result.content, 'utf8').toString('base64'),
    sha: currentSha,
    committer: {
      name: 'github-actions[bot]',
      email: '41898282+github-actions[bot]@users.noreply.github.com'
    },
    author: {
      name: 'github-actions[bot]',
      email: '41898282+github-actions[bot]@users.noreply.github.com'
    }
  });

  core.info(`Successfully recorded issue #${issueNumber} in CHANGELOG.md on develop.`);
}

module.exports = {
  SECTION_ORDER,
  cleanTitle,
  determineCategory,
  shouldProcessIssue,
  parseSections,
  formatSections,
  updateChangelogContent,
  run
};
