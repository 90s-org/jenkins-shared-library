def updateCommitStatus(String state, String description, String context = 'Jenkins CI') {
    withCredentials([string(credentialsId: 'github-token', variable: 'GITHUB_TOKEN')]) {
        def repoUrl = sh(script: 'git remote get-url origin', returnStdout: true).trim()
        def repoPath = repoUrl.replaceAll(/.*github\.com[\/:]/, '').replaceAll(/\.git$/, '')

        withEnv([
            "COMMIT_STATE=${state}",
            "COMMIT_DESC=${description}",
            "COMMIT_CONTEXT=${context}",
            "REPO_PATH=${repoPath}",
            "COMMIT_SHA=${env.GIT_COMMIT}",
            "BUILD_LINK=${env.BUILD_URL}"
        ]) {
            sh '''
                jq -n \
                    --arg state   "$COMMIT_STATE" \
                    --arg url     "$BUILD_LINK" \
                    --arg desc    "$COMMIT_DESC" \
                    --arg context "$COMMIT_CONTEXT" \
                    '{state: $state, target_url: $url, description: $desc, context: $context}' \
                | curl -sf \
                       -X POST \
                       -H "Authorization: Bearer $GITHUB_TOKEN" \
                       -H "Accept: application/vnd.github+json" \
                       -H "Content-Type: application/json" \
                       -H "X-GitHub-Api-Version: 2022-11-28" \
                       --data @- \
                       "https://api.github.com/repos/$REPO_PATH/statuses/$COMMIT_SHA"
            '''
        }
    }
}