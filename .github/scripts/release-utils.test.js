const test = require('node:test');
const assert = require('node:assert/strict');
const {
  calculateBranchMetadata,
  validateBranchTarget,
  computeNextVersion,
  stampChangelogRelease,
  initializeUnreleasedChangelog,
  extractReleaseNotes
} = require('./release-utils');

test('calculateBranchMetadata — issue branching simulation', async (t) => {
  await t.test('provisions release/* branch from develop and prepares auto-PR payload for release label', () => {
    const meta = calculateBranchMetadata({
      labelName: 'release',
      issueNumber: 35,
      rawTitle: 'feat(release): VoicePulse 0.1.0 production release',
      issueBody: '## Scope\n- Release candidate 0.1.0\n\nCloses #35'
    });

    assert.equal(meta.shouldCreate, true);
    assert.equal(meta.baseBranch, 'develop');
    assert.equal(meta.branchName, 'release/VP-35-voicepulse-0-1-0-production-re');
    assert.equal(meta.isRelease, true);
    assert.equal(meta.isMajor, false);
    assert.equal(meta.prTitle, '[VP-35]: feat(release): VoicePulse 0.1.0 production release');
    assert.match(meta.prBody, /Closes #35/);
  });

  await t.test('detects major release breaking changes in issue body', () => {
    const meta = calculateBranchMetadata({
      labelName: 'release',
      issueNumber: 36,
      rawTitle: '[VP-36] Release 1.0.0 breaking change',
      issueBody: '- [x] Major Release (Breaking Changes)\n\nCloses #36'
    });

    assert.equal(meta.shouldCreate, true);
    assert.equal(meta.isRelease, true);
    assert.equal(meta.isMajor, true);
    assert.equal(meta.prTitle, '[VP-36]: Release 1.0.0 breaking change');
  });

  await t.test('provisions hotfix/* branch from main without auto-PR', () => {
    const meta = calculateBranchMetadata({
      labelName: 'hotfix',
      issueNumber: 37,
      rawTitle: 'fix(gateway): resolve netty connection leak in production',
      issueBody: 'Production memory leak fix.'
    });

    assert.equal(meta.shouldCreate, true);
    assert.equal(meta.baseBranch, 'main');
    assert.equal(meta.branchName, 'hotfix/VP-37-resolve-netty-connection-leak');
    assert.equal(meta.isRelease, false);
  });

  await t.test('provisions bugfix/* branch from develop for bug label', () => {
    const meta = calculateBranchMetadata({
      labelName: 'bug',
      issueNumber: 38,
      rawTitle: 'fix(core): null pointer in audio buffer',
      issueBody: 'Bug details'
    });

    assert.equal(meta.shouldCreate, true);
    assert.equal(meta.baseBranch, 'develop');
    assert.equal(meta.branchName, 'bugfix/VP-38-null-pointer-in-audio-buffer');
    assert.equal(meta.isRelease, false);
  });

  await t.test('returns shouldCreate=false for non-triggering labels', () => {
    const meta = calculateBranchMetadata({
      labelName: 'wontfix',
      issueNumber: 39,
      rawTitle: 'Ignored issue',
      issueBody: ''
    });

    assert.equal(meta.shouldCreate, false);
  });
});

test('validateBranchTarget — branch target rule enforcement', async (t) => {
  await t.test('release/* branches MUST target main', () => {
    const valid = validateBranchTarget('release/VP-35-v0-1-0', 'main');
    assert.equal(valid.valid, true);
    assert.deepEqual(valid.labels, ['release']);

    const invalid = validateBranchTarget('release/VP-35-v0-1-0', 'develop');
    assert.equal(invalid.valid, false);
    assert.match(invalid.error, /MUST target 'main'/);
  });

  await t.test('hotfix/* branches MUST target main', () => {
    const valid = validateBranchTarget('hotfix/VP-33-fix', 'main');
    assert.equal(valid.valid, true);
    assert.deepEqual(valid.labels, ['hotfix']);

    const invalid = validateBranchTarget('hotfix/VP-33-fix', 'develop');
    assert.equal(invalid.valid, false);
    assert.match(invalid.error, /MUST target 'main'/);
  });

  await t.test('feature/* branches MUST target develop', () => {
    const valid = validateBranchTarget('feature/VP-32-test', 'develop');
    assert.equal(valid.valid, true);
    assert.deepEqual(valid.labels, ['enhancement']);

    const invalid = validateBranchTarget('feature/VP-32-test', 'main');
    assert.equal(invalid.valid, false);
    assert.match(invalid.error, /MUST target 'develop'/);
  });

  await t.test('bugfix/* branches MUST target develop', () => {
    const valid = validateBranchTarget('bugfix/VP-34-fix', 'develop');
    assert.equal(valid.valid, true);
    assert.deepEqual(valid.labels, ['bug']);

    const invalid = validateBranchTarget('bugfix/VP-34-fix', 'main');
    assert.equal(invalid.valid, false);
    assert.match(invalid.error, /MUST target 'develop'/);
  });

  await t.test('refactor/* and internal/* branches MUST target develop', () => {
    const refactorValid = validateBranchTarget('refactor/VP-10-cleanup', 'develop');
    assert.equal(refactorValid.valid, true);
    assert.deepEqual(refactorValid.labels, ['enhancement']);

    const internalValid = validateBranchTarget('internal/VP-12-tooling', 'develop');
    assert.equal(internalValid.valid, true);
    assert.deepEqual(internalValid.labels, ['tooling']);
  });

  await t.test('docs/* branches MUST target develop', () => {
    const valid = validateBranchTarget('docs/VP-15-rfc-update', 'develop');
    assert.equal(valid.valid, true);
    assert.deepEqual(valid.labels, ['documentation']);
  });

  await t.test('sync/main-to-develop-* MUST target develop', () => {
    const valid = validateBranchTarget('sync/main-to-develop-565d16e', 'develop');
    assert.equal(valid.valid, true);
    assert.deepEqual(valid.labels, ['sync']);

    const invalid = validateBranchTarget('sync/main-to-develop-565d16e', 'main');
    assert.equal(invalid.valid, false);
    assert.match(invalid.error, /MUST target 'develop'/);
  });

  await t.test('whitelists dependabot and copilot system branches', () => {
    const dep = validateBranchTarget('dependabot/maven/spring-boot-3.3.5', 'develop');
    assert.equal(dep.valid, true);
    assert.deepEqual(dep.labels, ['dependencies']);
  });

  await t.test('rejects disallowed legacy prefixes (feat/, fix/) and ill-formed branches', () => {
    const feat = validateBranchTarget('feat/my-branch', 'develop');
    assert.equal(feat.valid, false);
    assert.match(feat.error, /disallowed/i);

    const fix = validateBranchTarget('fix/my-bug', 'develop');
    assert.equal(fix.valid, false);
    assert.match(fix.error, /disallowed/i);

    const flat = validateBranchTarget('random-branch', 'develop');
    assert.equal(flat.valid, false);
    assert.match(flat.error, /conventions/i);
  });
});

test('computeNextVersion — semantic version bump calculations', async (t) => {
  await t.test('bumps minor version by default for planned release cycles', () => {
    const res = computeNextVersion('0.1.0');
    assert.equal(res.releaseVersion, '0.2.0');
    assert.equal(res.snapshotVersion, '0.2.0-SNAPSHOT');

    const resFromSnap = computeNextVersion('0.1.0-SNAPSHOT');
    assert.equal(resFromSnap.releaseVersion, '0.2.0');
    assert.equal(resFromSnap.snapshotVersion, '0.2.0-SNAPSHOT');
  });

  await t.test('bumps patch version when isHotfix or isPatch is set', () => {
    const res = computeNextVersion('0.1.0', { isHotfix: true });
    assert.equal(res.releaseVersion, '0.1.1');
    assert.equal(res.snapshotVersion, '0.1.1-SNAPSHOT');

    const resPatch = computeNextVersion('1.2.3', { isPatch: true });
    assert.equal(resPatch.releaseVersion, '1.2.4');
    assert.equal(resPatch.snapshotVersion, '1.2.4-SNAPSHOT');
  });

  await t.test('bumps major version when isMajor is set', () => {
    const res = computeNextVersion('0.1.0', { isMajor: true });
    assert.equal(res.releaseVersion, '1.0.0');
    assert.equal(res.snapshotVersion, '1.0.0-SNAPSHOT');

    const res2 = computeNextVersion('1.4.2', { isMajor: true });
    assert.equal(res2.releaseVersion, '2.0.0');
    assert.equal(res2.snapshotVersion, '2.0.0-SNAPSHOT');
  });

  await t.test('throws on malformed versions', () => {
    assert.throws(() => computeNextVersion('invalid'), /Invalid semver version/);
  });
});

test('stampChangelogRelease — stamps release header in CHANGELOG.md', async (t) => {
  await t.test('replaces ## [Unreleased] with version and date', () => {
    const input = '# Changelog\n\n## [Unreleased]\n\n### Added\n- Feature X';
    const res = stampChangelogRelease(input, '0.1.0', '2026-11-08');
    assert.equal(res.modified, true);
    assert.match(res.content, /## \[0\.1\.0\] - 2026-11-08/);
    assert.equal(res.content.includes('## [Unreleased]'), false);
  });

  await t.test('returns modified false if ## [Unreleased] is missing', () => {
    const input = '# Changelog\n\n## [0.1.0] - 2026-11-08';
    const res = stampChangelogRelease(input, '0.2.0', '2026-11-09');
    assert.equal(res.modified, false);
    assert.equal(res.content, input);
  });
});

test('initializeUnreleasedChangelog — initializes clean [Unreleased] section', async (t) => {
  await t.test('inserts ## [Unreleased] on top of previous release', () => {
    const input = '# Changelog\n\nHeader\n\n## [0.1.0] - 2026-11-08\n\n### Added\n- Feature X';
    const res = initializeUnreleasedChangelog(input);
    assert.equal(res.modified, true);
    assert.match(res.content, /## \[Unreleased\]\n\n## \[0\.1\.0\] - 2026-11-08/);
  });

  await t.test('is idempotent when ## [Unreleased] is already present', () => {
    const input = '# Changelog\n\n## [Unreleased]\n\n## [0.1.0] - 2026-11-08';
    const res = initializeUnreleasedChangelog(input);
    assert.equal(res.modified, false);
    assert.equal(res.content, input);
  });
});

test('extractReleaseNotes — extracts notes for a specific version', async (t) => {
  await t.test('extracts section markdown for designated release version', () => {
    const changelog = `# Changelog

## [0.2.0] - 2026-12-01

### Added
- Item in 0.2.0

## [0.1.0] - 2026-11-08

### Added
- Item 1 in 0.1.0
- Item 2 in 0.1.0

### Fixed
- Bugfix in 0.1.0

## [0.0.1] - 2026-10-01
`;

    const notes = extractReleaseNotes(changelog, '0.1.0');
    assert.equal(notes.includes('### Added'), true);
    assert.equal(notes.includes('Item 1 in 0.1.0'), true);
    assert.equal(notes.includes('Bugfix in 0.1.0'), true);
    assert.equal(notes.includes('Item in 0.2.0'), false);
    assert.equal(notes.includes('0.0.1'), false);
  });

  await t.test('returns empty string if version not found', () => {
    const changelog = '# Changelog\n\n## [0.1.0] - 2026-11-08\n\n- item';
    const notes = extractReleaseNotes(changelog, '9.9.9');
    assert.equal(notes, '');
  });
});
