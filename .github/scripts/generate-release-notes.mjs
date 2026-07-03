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

const conventionalCommitTypes = new Set([
  'build',
  'chore',
  'ci',
  'deps',
  'docs',
  'feat',
  'fix',
  'perf',
  'refactor',
  'style',
  'test',
]);

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
  lines.push(renderCommitBody(commit.body, commit.subject));
  lines.push('</details></li>');

  return lines.join('\n');
}

function renderCommitBody(body, subject) {
  const dependencyBody = renderDependencyUpdateBody(body, subject);
  if (dependencyBody) {
    return dependencyBody;
  }

  const paragraphs = body
    .split(/\r?\n[ \t]*\r?\n/)
    .map((paragraph) => paragraph.trim())
    .filter(Boolean)
    .map((paragraph) => `<p>${escapeReleaseText(paragraph).replace(/\r?\n/g, '<br>')}</p>`);
  return `<div class="commit-message-body">\n${paragraphs.join('\n')}\n</div>`;
}

function renderDependencyUpdateBody(body, subject) {
  const updates = parseDependencyUpdates(body, subject);
  if (updates.length === 0) {
    return '';
  }

  if (updates.length === 1) {
    return `<div class="commit-message-body">\n<p>${renderDependencyUpdate(updates[0])}</p>\n</div>`;
  }

  const items = updates.map((update) => `<li>${renderDependencyUpdate(update)}</li>`).join('\n');
  return `<div class="commit-message-body">\n<ul>\n${items}\n</ul>\n</div>`;
}

function renderDependencyUpdate(update) {
  let versionChange = '';
  if (update.from && update.to) {
    versionChange = `: ${escapeReleaseText(update.from)} -> ${escapeReleaseText(update.to)}`;
  } else if (update.to) {
    versionChange = `: to ${escapeReleaseText(update.to)}`;
  } else if (update.from) {
    versionChange = `: from ${escapeReleaseText(update.from)}`;
  }
  const changelogLink = update.changelogUrl
    ? ` <a href="${escapeHtml(update.changelogUrl)}">changelog</a>`
    : '';
  return `<strong>${escapeReleaseText(update.name)}</strong>${versionChange}${changelogLink}`;
}

function parseDependencyUpdates(body, subject) {
  if (!isDependencyUpdate(body, subject)) {
    return [];
  }

  const updates = [];
  const seen = new Set();
  const addUpdate = (update) => {
    if (!update.name || /dependency-updates group/i.test(update.name)) {
      return;
    }
    const key = `${update.name}\0${update.from}\0${update.to}`;
    if (seen.has(key)) {
      return;
    }
    seen.add(key);
    updates.push(update);
  };

  for (const update of parseDependencyTextUpdates(body)) {
    addUpdate(update);
  }

  for (const update of parseDependencyTableUpdates(body)) {
    addUpdate(update);
  }

  if (updates.length === 0) {
    const update = parseDependencySubject(subject, body);
    if (update) {
      addUpdate(update);
    }
  }

  return updates;
}

function isDependencyUpdate(body, subject) {
  return /updated-dependencies:/i.test(body) ||
    /^deps(?:\([^)]*\))?:\s*bump\b/i.test(subject) ||
    /^deps(?:\([^)]*\))?:\s*Update\b/i.test(subject) ||
    /\b(?:Bumps|Updates)\b[\s\S]*\bfrom\b[\s\S]*\bto\b/i.test(body);
}

function parseDependencyTableUpdates(body) {
  return body
    .split(/\r?\n/)
    .map((line) => {
      const match = line.match(/^\|\s*(?:\[([^\]]+)\]\(([^)]+)\)|`([^`]+)`|([^|`]+?))\s*\|\s*`?([^`|]+?)`?\s*\|\s*`?([^`|]+?)`?\s*\|$/);
      if (!match) {
        return undefined;
      }

      const [, linkedName, packageUrl, tickedName, plainName, from, to] = match;
      const name = normalizeDependencyName(linkedName || tickedName || plainName);
      if (!name || /^Package$/i.test(name) || /^-+$/.test(name)) {
        return undefined;
      }

      return {
        name,
        from: normalizeVersion(from),
        to: normalizeVersion(to),
        changelogUrl: '',
        packageUrl: packageUrl || '',
      };
    })
    .filter(Boolean)
    .map((update) => ({...update, changelogUrl: ''}));
}

function parseDependencyTextUpdates(body) {
  const updates = [];
  const pattern = /\b(?:Bumps|Updates)\s+(?:\[([^\]]+)\]\(([^)]+)\)|`([^`]+)`|(.+?))\s+from\s+`?([^\s`,]+)`?\s+to\s+`?([^\s`,]+)`?\.?/gi;
  let match;

  while ((match = pattern.exec(body)) !== null) {
    const [, linkedName, packageUrl, tickedName, plainName, from, to] = match;
    const name = normalizeDependencyName(linkedName || tickedName || plainName);
    if (!name) {
      continue;
    }

    const nextBody = body.slice(match.index);
    const nextUpdateIndex = nextBody.slice(1).search(/\n\s*(?:Bumps|Updates)\s+/i);
    const block = nextUpdateIndex === -1 ? nextBody : nextBody.slice(0, nextUpdateIndex + 1);
    updates.push(enrichDependencyLink({
      name,
      from: normalizeVersion(from),
      to: normalizeVersion(to),
      changelogUrl: '',
      packageUrl: packageUrl || '',
    }, block));
  }

  return updates;
}

function parseDependencySubject(subject, body) {
  const bumpMatch = subject.match(/\bbump\s+(.+?)\s+from\s+([^\s]+)\s+to\s+([^\s()]+)(?:\s+\([^)]*\))*\.?$/i) ||
    subject.match(/\bbump\s+(.+?)(?:\s+\([^)]*\))*\.?$/i);
  if (bumpMatch) {
    return enrichDependencyLink({
      name: normalizeDependencyName(bumpMatch[1]),
      from: normalizeVersion(bumpMatch[2] || ''),
      to: normalizeVersion(bumpMatch[3] || ''),
      changelogUrl: '',
      packageUrl: '',
    }, body);
  }

  const updateMatch = subject.match(/\bUpdate\s+(?:dependency\s+)?(.+?)\s+to\s+v?([^\s()]+)(?:\s+\([^)]*\))*$/i);
  if (updateMatch) {
    return enrichDependencyLink({
      name: normalizeDependencyName(updateMatch[1]),
      from: '',
      to: normalizeVersion(updateMatch[2] || ''),
      changelogUrl: '',
      packageUrl: '',
    }, body);
  }

  return undefined;
}

function enrichDependencyLink(update, body) {
  return {
    ...update,
    changelogUrl: findDependencyChangelogUrl(body) || '',
  };
}

function findDependencyChangelogUrl(body) {
  const changelog = body.match(/-\s+\[Changelog\]\(([^)]+)\)/i);
  if (changelog) {
    return changelog[1];
  }

  const releaseNotes = body.match(/-\s+\[Release notes\]\(([^)]+)\)/i);
  return releaseNotes ? releaseNotes[1] : '';
}

function normalizeDependencyName(value) {
  return normalize(value)
    .replace(/^the\s+/i, '')
    .replace(/\s+group(?:\s+across.*)?$/i, '')
    .replace(/\s+(?:Docker digest|action)$/i, '')
    .replace(/[,.]$/g, '')
    .replace(/^deps(?:\([^)]*\))?:\s*/i, '')
    .trim();
}

function normalizeVersion(value) {
  return normalize(value).replace(/^v(?=\d)/i, '').replace(/[,.]$/g, '');
}

function renderCommitSummary(subject, commitUrl, shortSha) {
  return `${escapeReleaseText(simplifyConventionalCommitSubject(subject))} (<a href="${commitUrl}"><code>${shortSha}</code></a>)`;
}

function simplifyConventionalCommitSubject(subject) {
  const match = subject.match(/^(\s*)([a-z]+)(?:\(([^)]+)\))?!?:\s+(.+)$/i);
  if (!match) {
    return subject;
  }

  const [, leading, type, scope, summary] = match;
  if (!conventionalCommitTypes.has(type.toLowerCase())) {
    return subject;
  }

  return scope ? `${leading}${scope}: ${summary}` : `${leading}${summary}`;
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
