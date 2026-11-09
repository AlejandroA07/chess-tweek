#!/usr/bin/env node

import { existsSync } from 'node:fs'
import { spawnSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'
import path from 'node:path'

const projectRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const javaHome =
  process.env.JAVA_HOME ??
  '/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home'

function fail(message) {
  console.error(`\n[verify] ${message}`)
  process.exit(1)
}

function run(label, command, args, extraEnv = {}) {
  console.log(`\n[verify] ${label}`)
  const result = spawnSync(command, args, {
    cwd: projectRoot,
    env: { ...process.env, CI: 'true', ...extraEnv },
    stdio: 'inherit',
  })

  if (result.error) fail(`${label} could not start: ${result.error.message}`)
  if (result.status !== 0) fail(`${label} exited with ${result.status}`)
}

const nodeMajor = Number.parseInt(process.versions.node.split('.')[0], 10)
if (nodeMajor !== 24) fail(`Node 24 is required; found ${process.versions.node}`)
if (!existsSync(path.join(javaHome, 'bin', 'java'))) {
  fail(`Java 25 was not found at ${javaHome}`)
}

run('locked frontend install', 'pnpm', ['install', '--frozen-lockfile'])
run('frontend tests', 'pnpm', ['frontend:test'])
run('frontend production build', 'pnpm', ['frontend:build'])
run('backend tests and package', './mvnw', ['clean', 'verify'], {
  JAVA_HOME: javaHome,
  PATH: `${path.join(javaHome, 'bin')}:${process.env.PATH ?? ''}`,
})
run('Git whitespace check', 'git', ['diff', '--check'])

console.log('\n[verify] PASS')
