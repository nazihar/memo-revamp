#!/usr/bin/env node
// SLSA v1 provenance predicate for the release images (research §R19, target Build L2: hosted build + signed
// provenance). Signed and attached by `cosign attest --type slsaprovenance1` in release.yml.
const env = process.env
const predicate = {
  buildDefinition: {
    buildType: 'https://github.com/nazihar/memo-revamp/.github/workflows/release.yml@v1',
    externalParameters: { ref: env.GITHUB_REF, repository: `${env.GITHUB_SERVER_URL}/${env.GITHUB_REPOSITORY}` },
    internalParameters: { runner: `${env.RUNNER_OS ?? 'unknown'}/${env.RUNNER_ARCH ?? 'unknown'}` },
    resolvedDependencies: [
      { uri: `git+${env.GITHUB_SERVER_URL}/${env.GITHUB_REPOSITORY}@${env.GITHUB_REF}`, digest: { gitCommit: env.GITHUB_SHA } },
    ],
  },
  runDetails: {
    builder: { id: `${env.GITHUB_SERVER_URL}/${env.GITHUB_REPOSITORY}/.github/workflows/release.yml` },
    metadata: {
      invocationId: `${env.GITHUB_SERVER_URL}/${env.GITHUB_REPOSITORY}/actions/runs/${env.GITHUB_RUN_ID}/attempts/${env.GITHUB_RUN_ATTEMPT}`,
      startedOn: new Date().toISOString(),
    },
  },
}
process.stdout.write(JSON.stringify(predicate, null, 2) + '\n')
