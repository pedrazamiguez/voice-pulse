const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const {
  resolveAutomationToken,
  buildSyncPrMetadata
} = require('../release-utils');

const ROOT_DIR = path.resolve(__dirname, '../../..');
const CREATE_BRANCH_WORKFLOW_PATH = path.join(
  ROOT_DIR,
  '.github/workflows/create-branch-from-issue.yml'
);
const SYNC_WORKFLOW_PATH = path.join(
  ROOT_DIR,
  '.github/workflows/sync-main-to-develop.yml'
);

const EXPECTED_TOKEN_EXPR =
  '${{ secrets.PAT_TOKEN || secrets.AUTOMATION_TOKEN || secrets.GITHUB_TOKEN }}';

test('resolveAutomationToken — token resolution and fallback priority', async (t) => {
  await t.test('prefers patToken when configured', () => {
    const token = resolveAutomationToken({
      patToken: 'ghp_maintainer_pat',
      automationToken: 'ghp_automation_pat',
      githubToken: 'ghs_default_token'
    });
    assert.equal(token, 'ghp_maintainer_pat');
  });

  await t.test('falls back to automationToken when patToken is absent or blank', () => {
    const token = resolveAutomationToken({
      patToken: '   ',
      automationToken: 'ghp_automation_pat',
      githubToken: 'ghs_default_token'
    });
    assert.equal(token, 'ghp_automation_pat');
  });

  await t.test('falls back to githubToken when neither PAT is configured', () => {
    const token = resolveAutomationToken({
      patToken: '',
      automationToken: undefined,
      githubToken: 'ghs_default_token'
    });
    assert.equal(token, 'ghs_default_token');
  });

  await t.test('supports two-argument (automationToken, githubToken) signature', () => {
    assert.equal(
      resolveAutomationToken('ghp_direct_pat', 'ghs_default_token'),
      'ghp_direct_pat'
    );
    assert.equal(
      resolveAutomationToken('', 'ghs_default_token'),
      'ghs_default_token'
    );
  });

  await t.test('throws when no valid token is provided', () => {
    assert.throws(
      () => resolveAutomationToken({ patToken: '', automationToken: '', githubToken: '' }),
      /No valid GitHub token available/
    );
  });
});

test('buildSyncPrMetadata — post-release sync commit and PR payload', async (t) => {
  await t.test('constructs sync branch, commitMessage without [skip ci], and PR metadata', () => {
    const meta = buildSyncPrMetadata({
      currentVersion: '0.5.0',
      snapshotVersion: '0.6.0-SNAPSHOT',
      shortSha: 'ddc7749'
    });

    assert.equal(meta.syncBranch, 'sync/main-to-develop-ddc7749');
    assert.equal(
      meta.commitMessage,
      'chore(sync): bump version to 0.6.0-SNAPSHOT and initialize unreleased changelog'
    );
    assert.equal(/\[skip ci\]|\[ci skip\]/i.test(meta.commitMessage), false);
    assert.equal(
      meta.prTitle,
      'chore(sync): merge main (0.5.0) into develop (0.6.0-SNAPSHOT)'
    );
    assert.match(meta.prBody, /Production Release:\*\* `0\.5\.0` \(commit `ddc7749`\)/);
    assert.match(meta.prBody, /Next Iteration:\*\* `0\.6\.0-SNAPSHOT`/);
    assert.match(meta.prBody, /Create a merge commit/);
    assert.deepEqual(meta.labels, ['sync']);
  });

  await t.test('throws when required parameters are missing', () => {
    assert.throws(
      () => buildSyncPrMetadata({ currentVersion: '', snapshotVersion: '0.6.0-SNAPSHOT', shortSha: 'ddc7749' }),
      /currentVersion, snapshotVersion, and shortSha are required/
    );
  });
});

test('GitHub Actions Workflow Contracts — Issue #116 release & sync PR CI gate', async (t) => {
  await t.test('create-branch-from-issue.yml authenticates checkout and github-script with PAT_TOKEN || AUTOMATION_TOKEN || GITHUB_TOKEN', () => {
    const content = fs.readFileSync(CREATE_BRANCH_WORKFLOW_PATH, 'utf8');

    const checkoutTokenMatches = content.match(
      /uses:\s*actions\/checkout@v4[\s\S]*?token:\s*\$\{\{\s*secrets\.PAT_TOKEN\s*\|\|\s*secrets\.AUTOMATION_TOKEN\s*\|\|\s*secrets\.GITHUB_TOKEN\s*\}\}/g
    );
    assert.ok(
      checkoutTokenMatches && checkoutTokenMatches.length >= 1,
      `Expected actions/checkout@v4 in create-branch-from-issue.yml to use ${EXPECTED_TOKEN_EXPR}`
    );

    const scriptTokenMatches = content.match(
      /github-token:\s*\$\{\{\s*secrets\.PAT_TOKEN\s*\|\|\s*secrets\.AUTOMATION_TOKEN\s*\|\|\s*secrets\.GITHUB_TOKEN\s*\}\}/g
    );
    assert.ok(
      scriptTokenMatches && scriptTokenMatches.length >= 2,
      `Expected both actions/github-script@v7 steps in create-branch-from-issue.yml to use ${EXPECTED_TOKEN_EXPR}`
    );
  });

  await t.test('sync-main-to-develop.yml authenticates checkout and github-script with PAT_TOKEN || AUTOMATION_TOKEN || GITHUB_TOKEN and omits [skip ci]', () => {
    const content = fs.readFileSync(SYNC_WORKFLOW_PATH, 'utf8');

    const checkoutTokenMatches = content.match(
      /uses:\s*actions\/checkout@v4[\s\S]*?token:\s*\$\{\{\s*secrets\.PAT_TOKEN\s*\|\|\s*secrets\.AUTOMATION_TOKEN\s*\|\|\s*secrets\.GITHUB_TOKEN\s*\}\}/g
    );
    assert.ok(
      checkoutTokenMatches && checkoutTokenMatches.length >= 1,
      `Expected actions/checkout@v4 in sync-main-to-develop.yml to use ${EXPECTED_TOKEN_EXPR}`
    );

    const scriptTokenMatches = content.match(
      /github-token:\s*\$\{\{\s*secrets\.PAT_TOKEN\s*\|\|\s*secrets\.AUTOMATION_TOKEN\s*\|\|\s*secrets\.GITHUB_TOKEN\s*\}\}/g
    );
    assert.ok(
      scriptTokenMatches && scriptTokenMatches.length >= 2,
      `Expected both actions/github-script@v7 steps in sync-main-to-develop.yml to use ${EXPECTED_TOKEN_EXPR}`
    );

    assert.equal(
      /\[skip ci\]|\[ci skip\]/i.test(content),
      false,
      'sync-main-to-develop.yml must not include [skip ci] in the sync commit message'
    );
  });
});
