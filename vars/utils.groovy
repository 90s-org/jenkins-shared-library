def updateCommitStatus(String state, String description, String context = 'Jenkins CI') {
    withCredentials([string(credentialsId: 'github-token', variable: 'GITHUB_TOKEN')]) {
        def repoUrl = sh(script: 'git remote get-url origin', returnStdout: true).trim()
        def repoPath = repoUrl.replaceAll(/.*github\.com[\/:]/, '').replaceAll(/\.git$/, '')

        // GitHub's Commit Status API only accepts lowercase state values
        // (error|failure|pending|success); anything else gets a 422.
        withEnv([
            "COMMIT_STATE=${state.toLowerCase()}",
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
                       -o /dev/null \
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

def validateCommitStatus(String commitSha, List requiredContexts) {
    withCredentials([string(credentialsId: 'github-token', variable: 'GITHUB_TOKEN')]) {
        def repoUrl = sh(script: 'git remote get-url origin', returnStdout: true).trim()
        def repoPath = repoUrl.replaceAll(/.*github\.com[\/:]/, '').replaceAll(/\.git$/, '')

        withEnv([
            "REPO_PATH=${repoPath}",
            "COMMIT_SHA=${commitSha}",
            "REQUIRED_CONTEXTS=${requiredContexts.join(',')}"
        ]) {
            sh '''
                set -e

                curl -sf \
                    -H "Authorization: Bearer $GITHUB_TOKEN" \
                    -H "Accept: application/vnd.github+json" \
                    -H "X-GitHub-Api-Version: 2022-11-28" \
                    "https://api.github.com/repos/$REPO_PATH/commits/$COMMIT_SHA/status" \
                    -o commit-status.json

                FAILED=0
                for CTX in $(echo "$REQUIRED_CONTEXTS" | tr ',' ' '); do
                    STATE=$(jq -r --arg ctx "$CTX" '.statuses[] | select(.context == $ctx) | .state' commit-status.json | head -n1)
                    echo "context=$CTX state=${STATE:-missing}"
                    if [ "$STATE" != "success" ]; then
                        FAILED=1
                    fi
                done

                rm -f commit-status.json

                if [ "$FAILED" -eq 1 ]; then
                    echo "One or more required status checks are not successful for commit $COMMIT_SHA"
                    exit 1
                fi
            '''
        }
    }
}

def createPullRequest(String base = 'main', String title = '', String body = '') {
    withCredentials([string(credentialsId: 'github-token', variable: 'GITHUB_TOKEN')]) {
        def repoUrl = sh(script: 'git remote get-url origin', returnStdout: true).trim()
        def repoPath = repoUrl.replaceAll(/.*github\.com[\/:]/, '').replaceAll(/\.git$/, '')
        def head = env.BRANCH_NAME

        withEnv([
            "REPO_PATH=${repoPath}",
            "PR_BASE=${base}",
            "PR_HEAD=${head}",
            "PR_TITLE=${title ?: "${head} -> ${base}"}",
            "PR_BODY=${body ?: "Automated PR from Jenkins build ${env.BUILD_URL}"}"
        ]) {
            sh '''
                set -e

                ORG="${REPO_PATH%%/*}"

                EXISTING=$(curl -sf \
                    -H "Authorization: Bearer $GITHUB_TOKEN" \
                    -H "Accept: application/vnd.github+json" \
                    "https://api.github.com/repos/$REPO_PATH/pulls?head=${ORG}:${PR_HEAD}&base=${PR_BASE}&state=open")

                COUNT=$(echo "$EXISTING" | jq 'length')

                if [ "$COUNT" -gt 0 ]; then
                    URL=$(echo "$EXISTING" | jq -r '.[0].html_url')
                    echo "PR already open: $URL"
                else
                    RESPONSE=$(jq -n \
                        --arg title "$PR_TITLE" \
                        --arg head  "$PR_HEAD" \
                        --arg base  "$PR_BASE" \
                        --arg body  "$PR_BODY" \
                        '{title: $title, head: $head, base: $base, body: $body}' \
                    | curl -sf \
                           -X POST \
                           -H "Authorization: Bearer $GITHUB_TOKEN" \
                           -H "Accept: application/vnd.github+json" \
                           -H "Content-Type: application/json" \
                           --data @- \
                           "https://api.github.com/repos/$REPO_PATH/pulls")
                    URL=$(echo "$RESPONSE" | jq -r '.html_url')
                    echo "Opened PR: $URL"
                fi
            '''
        }
    }
}