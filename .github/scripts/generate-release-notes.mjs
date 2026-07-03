#!/usr/bin/env node

import {execFileSync} from 'node:child_process';
import {appendFileSync} from 'node:fs';

const defaultSections = new Map([
  ['feat', 'Features'],
  ['fix', 'Bug Fixes'],
  ['perf', 'Performance Improvements'],
  ['docs', 'Documentation'],
  ['deps', 'Dependency updates'],
  ['refactor', 'Code Refactoring'],
  ['ci', 'Continuous Integration'],
  ['build', 'Build System'],
  ['test', 'Tests'],
  ['style', 'Styles'],
  ['chore', 'Chores'],
  ['revert', 'Reverts'],
]);

const sectionOrder = [
  'Features',
  'Bug Fixes',
  'Performance Improvements',
  'Documentation',
  'Dependency updates',
  'Code Refactoring',
  'Continuous Integration',
  'Build System',
  'Tests',
  'Styles',
  'Chores',
  'Reverts',
  'Changes',
];

const releaseVersion = requireEnv('RELEASE_VERSION');
const releaseTag = normalize(process.env.RELEASE_TAG) || releaseVersion;
const currentRef = normalize(process.env.CURRENT_REF) || releaseTag || 'HEAD';
const previousTag = resolvePreviousTag(
  normalize(process.env.PREVIOUS_TAG),
  releaseTag,
  currentRef,
  parseBoolean(process.env.ALLOW_PREVIOUS_TAG_FALLBACK, true)
);
const repository = normalize(process.env.GITHUB_REPOSITORY) || 'fluxzero-io/fluxzero-sdk-java';
const serverUrl = normalize(process.env.GITHUB_SERVER_URL) || 'https://github.com';
const repositoryUrl = `${serverUrl}/${repository}`;
const customSections = parseCustomReleaseRules(process.env.CUSTOM_RELEASE_RULES);

const commits = readCommits(previousTag, currentRef);
const releaseDate = readCommitDate(currentRef);
const releaseNotes = renderReleaseNotes(commits, releaseVersion, releaseTag, previousTag, releaseDate);

if (process.env.GITHUB_OUTPUT) {
  const delimiter = `release_notes_${Date.now()}_${Math.random().toString(36).slice(2)}`;
  appendFileSync(process.env.GITHUB_OUTPUT, `body<<${delimiter}\n${releaseNotes}\n${delimiter}\n`);
} else {
  console.log(releaseNotes);
}

function requireEnv(name) {
  const value = normalize(process.env[name]);
  if (!value) {
    throw new Error(`${name} is required`);
  }
  return value;
}

function normalize(value) {
  if (!value || value === 'undefined' || value === 'null') {
    return '';
  }
  return value.trim();
}

function parseBoolean(value, defaultValue) {
  const normalized = normalize(value).toLowerCase();
  if (!normalized) {
    return defaultValue;
  }
  return ['1', 'true', 'yes', 'y'].includes(normalized);
}

function parseCustomReleaseRules(rules) {
  const sections = new Map();
  for (const rule of (rules || '').split(',')) {
    const [type, , section] = rule.split(':');
    if (type && section) {
      sections.set(type.trim(), section.trim());
    }
  }
  return sections;
}

function resolvePreviousTag(configuredTag, releaseTag, ref, allowFallback) {
  if (configuredTag && tagExists(configuredTag)) {
    return configuredTag;
  }

  if (!allowFallback) {
    return '';
  }

  return git(['tag', '--merged', ref, '--sort=-version:refname'])
    .split(/\r?\n/)
    .map(normalize)
    .filter(Boolean)
    .filter((tag) => tag !== releaseTag)
    .at(0) || '';
}

function tagExists(tag) {
  try {
    git(['rev-parse', '--verify', '--quiet', `refs/tags/${tag}`]);
    return true;
  } catch {
    return false;
  }
}

function readCommits(baseTag, ref) {
  const range = baseTag ? `${baseTag}..${ref}` : ref;
  const rawLog = git(['log', '--format=%H%x1f%B%x1e', range]);
  return rawLog
    .split('\x1e')
    .map((record) => record.trim())
    .filter(Boolean)
    .map(parseCommitRecord);
}

function parseCommitRecord(record) {
  const [sha, fullMessage = ''] = record.split('\x1f');
  const normalizedMessage = fullMessage.trimEnd();
  const [subject = '', ...bodyLines] = normalizedMessage.split(/\r?\n/);
  return {
    sha: sha.trim(),
    subject,
    body: bodyLines.join('\n').trim(),
  };
}

function readCommitDate(ref) {
  return git(['log', '-1', '--format=%cs', ref]).trim();
}

function renderReleaseNotes(commits, version, tag, baseTag, date) {
  const lines = [];
  const compareUrl = baseTag
    ? `${repositoryUrl}/compare/${encodeURIComponent(baseTag)}...${encodeURIComponent(tag)}`
    : `${repositoryUrl}/releases/tag/${encodeURIComponent(tag)}`;

  lines.push(`## [${version}](${compareUrl}) (${date})`);
  lines.push('');

  if (commits.length === 0) {
    lines.push('No commits were found for this release.');
    return lines.join('\n');
  }

  const grouped = groupCommitsBySection(commits);
  for (const section of sortedSections(grouped)) {
    lines.push(`### ${section}`);
    lines.push('');
    lines.push('<ul>');

    for (const commit of grouped.get(section)) {
      lines.push(renderCommit(commit));
    }

    lines.push('</ul>');
    lines.push('');
  }

  return lines.join('\n').trimEnd();
}

function groupCommitsBySection(commits) {
  const grouped = new Map();
  for (const commit of commits) {
    const section = sectionFor(commit.subject);
    if (!grouped.has(section)) {
      grouped.set(section, []);
    }
    grouped.get(section).push(commit);
  }
  return grouped;
}

function sectionFor(subject) {
  const match = subject.match(/^(\w+)(?:\([^)]*\))?!?:\s/);
  if (!match) {
    return 'Changes';
  }

  const type = match[1];
  return customSections.get(type) || defaultSections.get(type) || 'Changes';
}

function sortedSections(grouped) {
  return [...grouped.keys()].sort((left, right) => {
    const leftIndex = sectionOrder.indexOf(left);
    const rightIndex = sectionOrder.indexOf(right);
    if (leftIndex === -1 && rightIndex === -1) {
      return left.localeCompare(right);
    }
    if (leftIndex === -1) {
      return 1;
    }
    if (rightIndex === -1) {
      return -1;
    }
    return leftIndex - rightIndex;
  });
}

function renderCommit(commit) {
  const shortSha = commit.sha.slice(0, 7);
  const commitUrl = `${repositoryUrl}/commit/${commit.sha}`;
  const summary = renderCommitSummary(commit.subject, commitUrl, shortSha);

  if (!commit.body) {
    return `<li>${summary}</li>`;
  }

  const lines = [
    `<li><details class="commit-message-details"><summary>${summary}</summary>`,
  ];
  lines.push(renderCommitBody(commit.body));
  lines.push('</details></li>');

  return lines.join('\n');
}

function renderCommitBody(body) {
  const paragraphs = body
    .split(/\r?\n[ \t]*\r?\n/)
    .map((paragraph) => paragraph.trim())
    .filter(Boolean)
    .map((paragraph) => `<p>${escapeReleaseText(paragraph).replace(/\r?\n/g, '<br>')}</p>`);
  return `<div class="commit-message-body">\n${paragraphs.join('\n')}\n</div>`;
}

function renderCommitSummary(subject, commitUrl, shortSha) {
  return `${escapeReleaseText(subject)} (<a href="${commitUrl}"><code>${shortSha}</code></a>)`;
}

function escapeReleaseText(value) {
  return preventGitHubMentions(escapeHtml(value));
}

function preventGitHubMentions(value) {
  return value.replace(
    /(^|[^\w])@([A-Za-z0-9](?:[A-Za-z0-9-]{0,37}[A-Za-z0-9])?)/g,
    '$1@&#8203;$2'
  );
}

function escapeHtml(value) {
  return value
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;')
    .replaceAll("'", '&#39;');
}

function git(args) {
  return execFileSync('git', args, {encoding: 'utf8'});
}
