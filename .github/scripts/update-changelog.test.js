/**
 * VoicePulse — Unit Tests for Automated CHANGELOG.md Maintenance Script
 */

const { describe, it } = require('node:test');
const assert = require('node:assert/strict');
const {
  SECTION_ORDER,
  cleanTitle,
  determineCategory,
  shouldProcessIssue,
  parseSections,
  formatSections,
  updateChangelogContent,
  run
} = require('./update-changelog.js');

describe('cleanTitle', () => {
  it('strips leading bracketed issue/epic tags', () => {
    assert.equal(
      cleanTitle('[VP-08] ci: configure GitHub Actions CI pipeline'),
      'ci: configure GitHub Actions CI pipeline'
    );
    assert.equal(
      cleanTitle('[EPIC-01] Engineering Foundations'),
      'Engineering Foundations'
    );
    assert.equal(
      cleanTitle('[EPIC-01][VP-01] feat(arch): initial project scaffolding'),
      'feat(arch): initial project scaffolding'
    );
  });

  it('leaves title intact when no bracketed prefix is present', () => {
    assert.equal(
      cleanTitle('ci(sonar): integrate SonarQube static analysis and quality gates'),
      'ci(sonar): integrate SonarQube static analysis and quality gates'
    );
    assert.equal(
      cleanTitle('fix: resolve Netty ByteBuf leak in WebSocket frame pipeline'),
      'fix: resolve Netty ByteBuf leak in WebSocket frame pipeline'
    );
  });

  it('handles empty or null title gracefully', () => {
    assert.equal(cleanTitle(''), '');
    assert.equal(cleanTitle(null), '');
  });
});

describe('determineCategory', () => {
  it('maps Conventional Commit prefixes to Keep a Changelog categories', () => {
    assert.equal(determineCategory('feat(arch): initial scaffolding'), 'Added');
    assert.equal(determineCategory('feature: add softphone'), 'Added');
    assert.equal(determineCategory('fix(core): fix audio buffer leak'), 'Fixed');
    assert.equal(determineCategory('bug: fix pnr parsing'), 'Fixed');
    assert.equal(determineCategory('refactor(infra): decouple state machine'), 'Changed');
    assert.equal(determineCategory('perf(vad): optimize energy calculation'), 'Changed');
    assert.equal(determineCategory('deprecate: old rest endpoint'), 'Deprecated');
    assert.equal(determineCategory('remove: legacy audio converter'), 'Removed');
    assert.equal(determineCategory('sec(auth): enforce token validation'), 'Security');
    assert.equal(determineCategory('security: patch tls configuration'), 'Security');
    assert.equal(determineCategory('ci: configure build workflow'), 'Tooling & Infrastructure');
    assert.equal(determineCategory('chore: update dependencies'), 'Tooling & Infrastructure');
    assert.equal(determineCategory('tooling: add dev-cli'), 'Tooling & Infrastructure');
    assert.equal(determineCategory('infra: add compose file'), 'Tooling & Infrastructure');
    assert.equal(determineCategory('build: configure maven wrapper'), 'Tooling & Infrastructure');
    assert.equal(determineCategory('docs: author changelog guide'), 'Documentation');
    assert.equal(determineCategory('doc(rfcs): add RFC-0003'), 'Documentation');
  });

  it('prioritizes Conventional Commit prefix over GitHub labels', () => {
    // Title is feat, but labeled with ci-cd and tooling -> Added
    assert.equal(
      determineCategory('feat(tooling): configure Maven Wrapper', [
        'ci-cd',
        'tooling',
        'enhancement'
      ]),
      'Added'
    );

    // Title is ci, but labeled with documentation -> Tooling & Infrastructure
    assert.equal(
      determineCategory('ci(changelog): automate CHANGELOG.md updates', [
        'documentation',
        'enhancement'
      ]),
      'Tooling & Infrastructure'
    );
  });

  it('falls back to GitHub labels when prefix is missing or unrecognized', () => {
    assert.equal(
      determineCategory('Author system engineering documentation', ['documentation']),
      'Documentation'
    );
    assert.equal(
      determineCategory('Resolve intermittent race condition in handoff', ['bug']),
      'Fixed'
    );
    assert.equal(
      determineCategory('Provide developer workstation bootstrap script', ['tooling']),
      'Tooling & Infrastructure'
    );
    assert.equal(
      determineCategory('Introduce new passenger verification state', ['enhancement']),
      'Added'
    );
    assert.equal(
      determineCategory('Refactor internal routing table storage', ['architecture']),
      'Changed'
    );
    assert.equal(
      determineCategory('Audit TLS certificate expiration handling', ['security']),
      'Security'
    );
  });

  it('defaults to Changed when neither prefix nor recognized labels match', () => {
    assert.equal(
      determineCategory('Update general project configurations', ['misc']),
      'Changed'
    );
  });
});

describe('shouldProcessIssue', () => {
  it('returns true for completed issue without exclusion labels', () => {
    assert.equal(
      shouldProcessIssue({ stateReason: 'completed', labels: ['enhancement', 'ci-cd'] }),
      true
    );
    assert.equal(
      shouldProcessIssue({ stateReason: null, labels: ['enhancement'] }),
      true
    );
  });

  it('returns false when stateReason is not_planned', () => {
    assert.equal(
      shouldProcessIssue({ stateReason: 'not_planned', labels: ['enhancement'] }),
      false
    );
  });

  it('returns false when issue is tagged with exclusion labels', () => {
    assert.equal(
      shouldProcessIssue({ stateReason: 'completed', labels: ['duplicate'] }),
      false
    );
    assert.equal(
      shouldProcessIssue({ stateReason: 'completed', labels: ['wontfix'] }),
      false
    );
    assert.equal(
      shouldProcessIssue({ stateReason: 'completed', labels: [{ name: 'invalid' }] }),
      false
    );
    assert.equal(
      shouldProcessIssue({ stateReason: 'completed', labels: ['release'] }),
      false
    );
    assert.equal(
      shouldProcessIssue({ stateReason: 'completed', labels: [{ name: 'Release' }] }),
      false
    );
  });

  it('returns false when title matches chore(release):', () => {
    assert.equal(
      shouldProcessIssue({
        stateReason: 'completed',
        labels: ['tooling'],
        title: 'chore(release): prepare release v0.2.0'
      }),
      false
    );
    assert.equal(
      shouldProcessIssue({
        stateReason: 'completed',
        labels: [],
        title: '[VP-40] chore(release): prepare release'
      }),
      false
    );
    assert.equal(
      shouldProcessIssue({
        stateReason: 'completed',
        labels: [],
        title: 'CHORE(RELEASE): cut release'
      }),
      false
    );
  });

  it('returns true for standard deliverables (feat:, fix:, docs:)', () => {
    assert.equal(
      shouldProcessIssue({
        stateReason: 'completed',
        labels: ['enhancement'],
        title: 'feat(gateway): implement websocket adapter'
      }),
      true
    );
    assert.equal(
      shouldProcessIssue({
        stateReason: 'completed',
        labels: ['bug'],
        title: 'fix(core): handle buffer underrun'
      }),
      true
    );
    assert.equal(
      shouldProcessIssue({
        stateReason: 'completed',
        labels: ['documentation'],
        title: 'docs(readme): update quickstart'
      }),
      true
    );
  });
});

describe('updateChangelogContent', () => {
  const sampleChangelog = `# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
- feat(arch): initial project scaffolding ([#1](https://github.com/pedrazamiguez/voice-pulse/issues/1))

### Tooling & Infrastructure
- ci: configure GitHub Actions CI pipeline ([#8](https://github.com/pedrazamiguez/voice-pulse/issues/8))
`;

  it('enforces idempotency when issue number is already recorded', () => {
    const result = updateChangelogContent(sampleChangelog, {
      issueNumber: 1,
      issueTitle: 'feat(arch): initial project scaffolding',
      issueUrl: 'https://github.com/pedrazamiguez/voice-pulse/issues/1'
    });

    assert.equal(result.modified, false);
    assert.equal(result.content, sampleChangelog);
    assert.match(result.message, /already recorded/i);
  });

  it('appends to an existing category section in ## [Unreleased]', () => {
    const result = updateChangelogContent(sampleChangelog, {
      issueNumber: 2,
      issueTitle: 'feat(qa): implement archunit architectural guardrails',
      issueUrl: 'https://github.com/pedrazamiguez/voice-pulse/issues/2'
    });

    assert.equal(result.modified, true);
    assert.equal(result.category, 'Added');
    assert.ok(
      result.content.includes(
        '- feat(qa): implement archunit architectural guardrails ([#2](https://github.com/pedrazamiguez/voice-pulse/issues/2))'
      )
    );
    // Ensure existing items remain
    assert.ok(result.content.includes('[#1]'));
    assert.ok(result.content.includes('[#8]'));
  });

  it('inserts a missing category section in strict Keep a Changelog order', () => {
    // Fixed belongs between Added and Tooling & Infrastructure
    const result = updateChangelogContent(sampleChangelog, {
      issueNumber: 10,
      issueTitle: 'fix(core): resolve audio buffer leak under high load',
      issueUrl: 'https://github.com/pedrazamiguez/voice-pulse/issues/10'
    });

    assert.equal(result.modified, true);
    assert.equal(result.category, 'Fixed');

    const addedIndex = result.content.indexOf('### Added');
    const fixedIndex = result.content.indexOf('### Fixed');
    const toolingIndex = result.content.indexOf('### Tooling & Infrastructure');

    assert.ok(addedIndex !== -1, 'Added section exists');
    assert.ok(fixedIndex !== -1, 'Fixed section exists');
    assert.ok(toolingIndex !== -1, 'Tooling section exists');

    assert.ok(addedIndex < fixedIndex, 'Added comes before Fixed');
    assert.ok(fixedIndex < toolingIndex, 'Fixed comes before Tooling & Infrastructure');
  });

  it('inserts Documentation section at the proper position after Tooling & Infrastructure', () => {
    const result = updateChangelogContent(sampleChangelog, {
      issueNumber: 30,
      issueTitle: 'docs(changelog): document automated changelog workflow',
      issueUrl: 'https://github.com/pedrazamiguez/voice-pulse/issues/30'
    });

    assert.equal(result.modified, true);
    assert.equal(result.category, 'Documentation');

    const toolingIndex = result.content.indexOf('### Tooling & Infrastructure');
    const docsIndex = result.content.indexOf('### Documentation');

    assert.ok(toolingIndex !== -1);
    assert.ok(docsIndex !== -1);
    assert.ok(toolingIndex < docsIndex, 'Tooling comes before Documentation');
  });

  it('preserves existing released versions below ## [Unreleased]', () => {
    const changelogWithReleases = sampleChangelog + `
## [0.1.0] - 2026-11-01

### Added
- Historic feature 1
`;
    const result = updateChangelogContent(changelogWithReleases, {
      issueNumber: 15,
      issueTitle: 'ci(test): add extra checks',
      issueUrl: 'https://github.com/pedrazamiguez/voice-pulse/issues/15'
    });

    assert.equal(result.modified, true);
    assert.ok(result.content.includes('## [0.1.0] - 2026-11-01'));
    assert.ok(result.content.includes('Historic feature 1'));
  });
});

describe('run (Octokit Integration)', () => {
  it('fetches develop CHANGELOG.md, updates content, and commits', async () => {
    const originalContent = `# Changelog\n\n## [Unreleased]\n\n### Added\n- Existing ([#1](url))\n`;
    let updatedPayload = null;

    const mockGithub = {
      rest: {
        repos: {
          getContent: async ({ owner, repo, path, ref }) => {
            assert.equal(owner, 'pedrazamiguez');
            assert.equal(repo, 'voice-pulse');
            assert.equal(path, 'CHANGELOG.md');
            assert.equal(ref, 'develop');
            return {
              data: {
                content: Buffer.from(originalContent).toString('base64'),
                sha: 'mock-sha-1234'
              }
            };
          },
          createOrUpdateFileContents: async (payload) => {
            updatedPayload = payload;
            return { data: { commit: { sha: 'new-commit-sha' } } };
          }
        }
      }
    };

    const mockContext = {
      repo: { owner: 'pedrazamiguez', repo: 'voice-pulse' },
      payload: {
        issue: {
          number: 29,
          title: 'ci(changelog): automate CHANGELOG.md updates upon issue completion',
          html_url: 'https://github.com/pedrazamiguez/voice-pulse/issues/29',
          state_reason: 'completed',
          labels: ['ci-cd', 'tooling']
        }
      }
    };

    const mockCore = {
      info: () => {},
      setFailed: (msg) => { throw new Error(msg); }
    };

    await run({ github: mockGithub, context: mockContext, core: mockCore });

    assert.ok(updatedPayload !== null, 'createOrUpdateFileContents should have been called');
    assert.equal(updatedPayload.branch, 'develop');
    assert.equal(
      updatedPayload.message,
      'docs(changelog): record completion of #29'
    );
    assert.equal(updatedPayload.sha, 'mock-sha-1234');
    assert.equal(updatedPayload.author.name, 'github-actions[bot]');

    const decodedContent = Buffer.from(updatedPayload.content, 'base64').toString('utf8');
    assert.ok(decodedContent.includes('#29'));
    assert.ok(decodedContent.includes('### Tooling & Infrastructure'));
  });

  it('skips release issue without updating CHANGELOG.md via Octokit', async () => {
    let getContentCalled = false;
    let createOrUpdateCalled = false;

    const mockGithub = {
      rest: {
        repos: {
          getContent: async () => {
            getContentCalled = true;
            return { data: { content: '', sha: 'sha' } };
          },
          createOrUpdateFileContents: async () => {
            createOrUpdateCalled = true;
          }
        }
      }
    };

    const loggedMessages = [];
    const mockCore = {
      info: (msg) => { loggedMessages.push(msg); },
      setFailed: (msg) => { throw new Error(msg); }
    };

    // Case 1: labeled as release
    const mockContextReleaseLabel = {
      repo: { owner: 'pedrazamiguez', repo: 'voice-pulse' },
      payload: {
        issue: {
          number: 40,
          title: 'chore(release): prepare release v0.1.0',
          html_url: 'https://github.com/pedrazamiguez/voice-pulse/issues/40',
          state_reason: 'completed',
          labels: ['release']
        }
      }
    };

    await run({ github: mockGithub, context: mockContextReleaseLabel, core: mockCore });
    assert.equal(getContentCalled, false);
    assert.equal(createOrUpdateCalled, false);
    assert.ok(loggedMessages.some(m => m.includes('Issue #40 skipped')));

    // Case 2: title matching chore(release): without release label
    const mockContextReleaseTitle = {
      repo: { owner: 'pedrazamiguez', repo: 'voice-pulse' },
      payload: {
        issue: {
          number: 46,
          title: 'chore(release): prepare release',
          html_url: 'https://github.com/pedrazamiguez/voice-pulse/issues/46',
          state_reason: 'completed',
          labels: ['tooling']
        }
      }
    };

    await run({ github: mockGithub, context: mockContextReleaseTitle, core: mockCore });
    assert.equal(getContentCalled, false);
    assert.equal(createOrUpdateCalled, false);
    assert.ok(loggedMessages.some(m => m.includes('Issue #46 skipped')));
  });
});
