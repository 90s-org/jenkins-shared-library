def call (Map configMap){
    pipeline {
        agent {
            node {
                label 'ROBOSHOP'
            }
        }
        parameters {
            // 'dev' is first, so it's the default when this build is triggered
            // automatically by a push to main (no parameters supplied).
            choice(name: 'ENVIRONMENT', choices: ['dev', 'sit', 'uat', 'prod'], description: 'Environment to deploy to')
            string(name: 'COMMIT_ID', defaultValue: '', description: 'Commit SHA to promote (required for sit/uat/prod)')
            string(name: 'COMPONENT', defaultValue: 'catalogue', description: 'Component to deploy')
            string(name: 'PROJECT', defaultValue: 'roboshop', description: 'Project name')
            string(name: 'CR_NUMBER', defaultValue: '', description: 'Change Request number (required for prod deploy)')
            string(name: 'VERSION', defaultValue: '', description: 'Release version to tag the commit with on a successful prod deploy, e.g. v1.4.0 (required for prod deploy)')
            string(name: 'ISSUE_KEY', defaultValue: '', description: 'Jira ticket key this build should update (set automatically by the dev pipeline when it creates the ticket; leave blank for manual promotions with no Jira tracking)')
        }
        environment {
            def appVersion = ""
            def shortCommit = ""
            def issueKey = ""
            acc_id = "160885265516"
            project = configMap.get("project")
            component = configMap.get("component")
            org = "90s-org"
            JIRA_SITE = "roboshop-jira"
            jiraProjectKey = "D88S"
        }
        options {
            disableConcurrentBuilds()
            timeout(time: 15, unit: 'MINUTES')
        }
        // Branch jobs under this multibranch project have their whole Configure page
        // locked (computed from the Jenkinsfile scan), so "Trigger builds remotely"
        // can't be set via the UI at all. Declaring the trigger here instead means it's
        // regenerated from code on every scan — Jira Automation calls this webhook
        // directly. The 'jira-webhook-token' credential is a Secret text credential;
        // its value is whatever gets passed as ?token=... in the webhook URL.
        triggers {
            GenericTrigger(
                genericVariables: [
                    [key: 'ENVIRONMENT', value: '$.ENVIRONMENT'],
                    [key: 'COMMIT_ID', value: '$.COMMIT_ID'],
                    [key: 'COMPONENT', value: '$.COMPONENT'],
                    [key: 'PROJECT', value: '$.PROJECT'],
                    [key: 'ISSUE_KEY', value: '$.ISSUE_KEY'],
                    [key: 'VERSION', value: '$.VERSION'],
                    [key: 'CR_NUMBER', value: '$.CR_NUMBER']
                ],
                tokenCredentialId: 'jira-secret',
                causeString: 'Triggered by Jira Automation',
                printContributedVariables: true,
                printPostContent: true
            )
        }
        stages {
            // GenericTrigger populates env.* from the webhook JSON body, not params.* —
            // every stage below gates on params.*, so a webhook-triggered run can't do
            // the real work directly. Instead it just relays into a second, properly
            // parameterized build of this same job and stops. Manual/internal builds
            // (no GenericCause) skip straight to the 'pipeline' stage below.
            stage('jira-webhook-relay') {
                when {
                    expression { !currentBuild.getBuildCauses('org.jenkinsci.plugins.gwt.GenericCause').isEmpty() }
                }
                steps {
                    script {
                        build job: env.JOB_NAME, parameters: [
                            string(name: 'ENVIRONMENT', value: env.ENVIRONMENT ?: 'dev'),
                            string(name: 'COMMIT_ID', value: env.COMMIT_ID ?: ''),
                            string(name: 'COMPONENT', value: env.COMPONENT ?: 'catalogue'),
                            string(name: 'PROJECT', value: env.PROJECT ?: 'roboshop'),
                            string(name: 'ISSUE_KEY', value: env.ISSUE_KEY ?: ''),
                            string(name: 'VERSION', value: env.VERSION ?: ''),
                            string(name: 'CR_NUMBER', value: env.CR_NUMBER ?: '')
                        ], wait: false
                    }
                }
            }
            stage('pipeline') {
                when {
                    expression { currentBuild.getBuildCauses('org.jenkinsci.plugins.gwt.GenericCause').isEmpty() }
                }
                stages {
            // Re-verify the merged commit in dev: redeploy the image built on the
            // feature branch and re-run api-tests against it before it's considered good.
            stage('read-version'){
                when {
                    expression { params.ENVIRONMENT == 'dev' }
                }
                steps{
                    script {
                        def packageJson = readJSON file: 'package.json'
                        appVersion = packageJson.version
                        echo "The application version is: ${appVersion}"
                    }
                }
            }
            stage('dev-deploy') {
                when {
                    expression { params.ENVIRONMENT == 'dev' }
                }
                steps {
                    script {
                        try {
                            withAWS(credentials: 'aws-creds', region: 'us-east-1') {
                                sh """
                                    aws eks update-kubeconfig --name roboshop --region us-east-1

                                    helm upgrade --install ${component} ./helm \
                                        -f ./helm/values-dev.yaml \
                                        --namespace roboshop-dev \
                                        --create-namespace \
                                        --set deployment.imageVersion=${appVersion} \
                                        --wait --timeout 5m

                                    kubectl rollout status deployment/${component} -n roboshop-dev --timeout=120s
                                """
                            }
                            utils.updateCommitStatus('success', 'Deployed to roboshop-dev', 'dev-deploy')
                        }
                        catch (Exception e) {
                            utils.updateCommitStatus('failure', 'Deploy to roboshop-dev failed', 'dev-deploy')
                            throw e
                        }
                    }
                }
            }
            stage('api-tests') {
                when {
                    expression { params.ENVIRONMENT == 'dev' }
                }
                steps {
                    script {
                        try {
                            build job: 'ROBOSHOP/catalogue-api-tests', parameters: [
                                string(name: 'NAMESPACE', value: 'roboshop-dev'),
                                string(name: 'COMMIT_ID', value: env.GIT_COMMIT)
                            ], wait: true, propagate: true
                            utils.updateCommitStatus('success', 'catalogue-api-tests passed', 'api-tests')
                        }
                        catch (Exception e) {
                            utils.updateCommitStatus('failure', 'catalogue-api-tests failed', 'api-tests')
                            throw e
                        }
                    }
                }
            }
            // Dev-deploy and api-tests passed against this commit — open the Jira ticket
            // that tracks it through SIT/UAT/PROD, carrying the commit and version so
            // nobody has to type them in by hand later.
            stage('create-jira-ticket') {
                when {
                    expression { params.ENVIRONMENT == 'dev' }
                }
                steps {
                    script {
                        shortCommit = env.GIT_COMMIT.take(7)
                        issueKey = utils.createJiraTicket(jiraProjectKey, shortCommit, appVersion)
                        echo "Created Jira ticket ${issueKey} for ${shortCommit} / ${appVersion}"
                    }
                }
            }
            // Promote the same image that just passed dev/api-tests by retagging it
            // with the short commit SHA in ECR.
            stage('promote-image') {
                when {
                    expression { params.ENVIRONMENT == 'dev' }
                }
                steps {
                    script {
                        try {
                            withAWS(credentials: 'aws-creds', region: 'us-east-1') {
                                sh """
                                    aws ecr get-login-password --region us-east-1 | docker login --username AWS --password-stdin ${acc_id}.dkr.ecr.us-east-1.amazonaws.com

                                    docker pull ${acc_id}.dkr.ecr.us-east-1.amazonaws.com/${project}/${component}:${appVersion}
                                    docker tag ${acc_id}.dkr.ecr.us-east-1.amazonaws.com/${project}/${component}:${appVersion} ${acc_id}.dkr.ecr.us-east-1.amazonaws.com/${project}/${component}:${shortCommit}
                                    docker push ${acc_id}.dkr.ecr.us-east-1.amazonaws.com/${project}/${component}:${shortCommit}
                                """
                            }
                            utils.updateCommitStatus('success', "Promoted image as ${shortCommit}", 'promote-image')
                        }
                        catch (Exception e) {
                            utils.updateCommitStatus('failure', 'Image promotion failed', 'promote-image')
                            throw e
                        }
                    }
                }
            }
            // Dev's job ends here — the ticket sits at Trigger SIT (its creation
            // status). SIT/UAT/PROD are all started the same way from here on: a
            // Jira Automation rule (Issue created for SIT, Issue transitioned for
            // UAT/PROD) fires the webhook, which relays into a real build via
            // 'jira-webhook-relay' above.
            // Manual promotion path: pick ENVIRONMENT (sit/uat/prod), give the commit
            // that was already verified in dev, and the target component/project.
            // Each stage further down the chain (sit-deploy, sit-integration-tests, ...)
            // gates the next environment, so the required contexts grow with the target.
            stage('validate-commit-status') {
                when {
                    expression { params.ENVIRONMENT in ['sit', 'uat', 'prod'] }
                }
                steps {
                    script {
                        if (!params.COMMIT_ID?.trim()) {
                            error("COMMIT_ID is required when deploying to ${params.ENVIRONMENT}")
                        }
                        shortCommit = params.COMMIT_ID.trim().take(7)

                        def requiredContexts = ['dev-deploy', 'api-tests']
                        if (params.ENVIRONMENT in ['uat', 'prod']) {
                            requiredContexts += ['sit-deploy', 'sit-integration-tests']
                        }
                        if (params.ENVIRONMENT == 'prod') {
                            requiredContexts += ['uat-deploy', 'uat-regression-tests']
                        }
                        utils.validateCommitStatus(params.COMMIT_ID.trim(), requiredContexts)
                    }
                }
            }
            stage('sit-deploy') {
                when {
                    expression { params.ENVIRONMENT == 'sit' }
                }
                steps {
                    script {
                        try {
                            withAWS(credentials: 'aws-creds', region: 'us-east-1') {
                                sh """
                                    aws eks update-kubeconfig --name roboshop --region us-east-1

                                    helm upgrade --install ${params.COMPONENT} ./helm \
                                        -f ./helm/values-sit.yaml \
                                        --namespace roboshop-sit \
                                        --create-namespace \
                                        --set deployment.imageVersion=${shortCommit} \
                                        --wait --timeout 5m

                                    kubectl rollout status deployment/${params.COMPONENT} -n roboshop-sit --timeout=120s
                                """
                            }
                            utils.updateCommitStatus('success', "Deployed ${shortCommit} to roboshop-sit", 'sit-deploy')
                        }
                        catch (Exception e) {
                            utils.updateCommitStatus('failure', 'Deploy to roboshop-sit failed', 'sit-deploy')
                            if (params.ISSUE_KEY?.trim()) {
                                utils.transitionJiraIssue(params.ISSUE_KEY.trim(), 'Trigger SIT')
                            }
                            throw e
                        }
                    }
                }
            }
            stage('sit-integration-tests') {
                when {
                    expression { params.ENVIRONMENT == 'sit' }
                }
                steps {
                    script {
                        try {
                            withAWS(credentials: 'aws-creds', region: 'us-east-1') {
                                sh "aws eks update-kubeconfig --name roboshop --region us-east-1"

                                // The Jenkins agent can't resolve *.svc.cluster.local from
                                // roboshop-sit, but it's in the same VPC as the EKS pods —
                                // route by pod IP instead of relying on cluster DNS.
                                def catalogueIp = utils.getPodIP('roboshop-sit', 'catalogue')
                                def cartIp      = utils.getPodIP('roboshop-sit', 'cart')
                                def userIp      = utils.getPodIP('roboshop-sit', 'user')
                                def shippingIp  = utils.getPodIP('roboshop-sit', 'shipping')
                                def paymentIp   = utils.getPodIP('roboshop-sit', 'payment')

                                build job: 'ROBOSHOP/roboshop-integration-tests', parameters: [
                                    string(name: 'NAMESPACE', value: 'roboshop-sit'),
                                    string(name: 'CATALOGUE_URL', value: "http://${catalogueIp}:8080"),
                                    string(name: 'CART_URL', value: "http://${cartIp}:8080"),
                                    string(name: 'USER_URL', value: "http://${userIp}:8080"),
                                    string(name: 'SHIPPING_URL', value: "http://${shippingIp}:8080"),
                                    string(name: 'PAYMENT_URL', value: "http://${paymentIp}:8080")
                                ], wait: true, propagate: true
                            }
                            utils.updateCommitStatus('success', 'roboshop-integration-tests passed', 'sit-integration-tests')
                            if (params.ISSUE_KEY?.trim()) {
                                utils.transitionJiraIssue(params.ISSUE_KEY.trim(), 'SIT Done')
                            }
                        }
                        catch (Exception e) {
                            utils.updateCommitStatus('failure', 'roboshop-integration-tests failed', 'sit-integration-tests')
                            if (params.ISSUE_KEY?.trim()) {
                                utils.transitionJiraIssue(params.ISSUE_KEY.trim(), 'Trigger SIT')
                            }
                            throw e
                        }
                    }
                }
            }
            stage('uat-deploy') {
                when {
                    expression { params.ENVIRONMENT == 'uat' }
                }
                steps {
                    script {
                        try {
                            withAWS(credentials: 'aws-creds', region: 'us-east-1') {
                                sh """
                                    aws eks update-kubeconfig --name roboshop --region us-east-1

                                    helm upgrade --install ${params.COMPONENT} ./helm \
                                        -f ./helm/values-uat.yaml \
                                        --namespace roboshop-uat \
                                        --create-namespace \
                                        --set deployment.imageVersion=${shortCommit} \
                                        --wait --timeout 5m

                                    kubectl rollout status deployment/${params.COMPONENT} -n roboshop-uat --timeout=120s
                                """
                            }
                            utils.updateCommitStatus('success', "Deployed ${shortCommit} to roboshop-uat", 'uat-deploy')
                        }
                        catch (Exception e) {
                            utils.updateCommitStatus('failure', 'Deploy to roboshop-uat failed', 'uat-deploy')
                            if (params.ISSUE_KEY?.trim()) {
                                utils.transitionJiraIssue(params.ISSUE_KEY.trim(), 'Trigger UAT')
                            }
                            throw e
                        }
                    }
                }
            }
            stage('uat-regression-tests') {
                when {
                    expression { params.ENVIRONMENT == 'uat' }
                }
                steps {
                    script {
                        try {
                            withAWS(credentials: 'aws-creds', region: 'us-east-1') {
                                sh "aws eks update-kubeconfig --name roboshop --region us-east-1"

                                // The Jenkins agent can't resolve *.svc.cluster.local from
                                // roboshop-sit, but it's in the same VPC as the EKS pods —
                                // route by pod IP instead of relying on cluster DNS.
                                def catalogueIp = utils.getPodIP('roboshop-uat', 'catalogue')
                                def cartIp      = utils.getPodIP('roboshop-uat', 'cart')
                                def userIp      = utils.getPodIP('roboshop-uat', 'user')
                                def shippingIp  = utils.getPodIP('roboshop-uat', 'shipping')
                                def paymentIp   = utils.getPodIP('roboshop-uat', 'payment')

                                build job: 'ROBOSHOP/roboshop-regression-tests', parameters: [
                                    string(name: 'NAMESPACE', value: 'roboshop-uat'),
                                    string(name: 'CATALOGUE_URL', value: "http://${catalogueIp}:8080"),
                                    string(name: 'CART_URL', value: "http://${cartIp}:8080"),
                                    string(name: 'USER_URL', value: "http://${userIp}:8080"),
                                    string(name: 'SHIPPING_URL', value: "http://${shippingIp}:8080"),
                                    string(name: 'PAYMENT_URL', value: "http://${paymentIp}:8080")
                                ], wait: true, propagate: true
                            }
                            utils.updateCommitStatus('success', 'roboshop-regression-tests passed', 'uat-regression-tests')
                            if (params.ISSUE_KEY?.trim()) {
                                utils.transitionJiraIssue(params.ISSUE_KEY.trim(), 'UAT Done')
                            }
                        }
                        catch (Exception e) {
                            utils.updateCommitStatus('failure', 'roboshop-regression-tests failed', 'uat-regression-tests')
                            if (params.ISSUE_KEY?.trim()) {
                                utils.transitionJiraIssue(params.ISSUE_KEY.trim(), 'Trigger UAT')
                            }
                            throw e
                        }
                    }
                }
            }
            // CR gate: number + version must be supplied, deploy must fall inside the
            // approved window, and a human has to click approve. Runs with its own
            // timeout so waiting on a person doesn't get killed by the pipeline's
            // overall 15-minute budget.
            stage('change-request-check') {
                when {
                    expression { params.ENVIRONMENT == 'prod' }
                }
                options {
                    timeout(time: 4, unit: 'HOURS')
                }
                steps {
                    script {
                        if (!params.CR_NUMBER?.trim()) {
                            error("CR_NUMBER is required for a prod deploy")
                        }
                        if (!params.VERSION?.trim()) {
                            error("VERSION is required for a prod deploy")
                        }

                        // Dummy deployment-window check — placeholder until this is wired
                        // up to a real CR system. Blocks weekend prod deploys for now.
                        def dayOfWeek = sh(script: 'date +%u', returnStdout: true).trim().toInteger()
                        if (dayOfWeek >= 6) {
                            error("CR ${params.CR_NUMBER}: outside the approved deployment window (no weekend prod deploys) — dummy check, replace with a real CR window lookup")
                        }
                        echo "CR ${params.CR_NUMBER}: within deployment window"

                        input message: "Approve prod deploy of ${params.COMPONENT}@${shortCommit} as ${params.VERSION} under CR ${params.CR_NUMBER}?", ok: 'Approve'
                    }
                }
            }
            stage('prod-deploy') {
                when {
                    expression { params.ENVIRONMENT == 'prod' }
                }
                steps {
                    script {
                        withAWS(credentials: 'aws-creds', region: 'us-east-1') {
                            sh "aws eks update-kubeconfig --name roboshop --region us-east-1"

                            // Only attempt a rollback if there's a prior successful release
                            // to roll back to — a failed first-ever deploy has nothing behind it.
                            def releaseExists = sh(
                                script: "helm status ${params.COMPONENT} -n roboshop-prod > /dev/null 2>&1",
                                returnStatus: true
                            ) == 0

                            try {
                                sh """
                                    helm upgrade --install ${params.COMPONENT} ./helm \
                                        -f ./helm/values-prod.yaml \
                                        --namespace roboshop-prod \
                                        --create-namespace \
                                        --set deployment.imageVersion=${shortCommit} \
                                        --wait --timeout 5m

                                    kubectl rollout status deployment/${params.COMPONENT} -n roboshop-prod --timeout=120s
                                """
                                utils.updateCommitStatus('success', "Deployed ${shortCommit} to roboshop-prod (CR ${params.CR_NUMBER})", 'prod-deploy')
                            }
                            catch (Exception e) {
                                if (releaseExists) {
                                    echo "prod-deploy failed on an existing release — rolling back ${params.COMPONENT} in roboshop-prod"
                                    sh "helm rollback ${params.COMPONENT} 0 -n roboshop-prod --wait --timeout 5m"
                                } else {
                                    echo "prod-deploy failed on the first-ever release of ${params.COMPONENT} — nothing to roll back to"
                                }
                                utils.updateCommitStatus('failure', 'Deploy to roboshop-prod failed', 'prod-deploy')
                                if (params.ISSUE_KEY?.trim()) {
                                    utils.transitionJiraIssue(params.ISSUE_KEY.trim(), 'Trigger PROD')
                                }
                                throw e
                            }
                        }
                    }
                }
            }
            // Only reached if prod-deploy succeeded — declarative pipeline stops
            // running further stages once one fails.
            stage('tag-release') {
                when {
                    expression { params.ENVIRONMENT == 'prod' }
                }
                steps {
                    script {
                        utils.tagCommit(params.COMMIT_ID.trim(), params.VERSION.trim())
                        echo "Tagged ${shortCommit} as ${params.VERSION} (CR ${params.CR_NUMBER})"
                        if (params.ISSUE_KEY?.trim()) {
                            utils.transitionJiraIssue(params.ISSUE_KEY.trim(), 'Completed')
                        }
                    }
                }
            }
                } // end nested stages (real pipeline logic)
            } // end stage('pipeline')
        }

        post {
            success {
                /* slackSend(
                    channel: '#test-ci',
                    color: 'good',
                    tokenCredentialId: 'slack-token',
                    message: "✅ *${params.COMPONENT ?: component}* ${params.ENVIRONMENT} deploy succeeded — commit `${shortCommit ?: env.GIT_COMMIT.take(7)}` (<${env.BUILD_URL}console|console>)"
                ) */
                echo "success"
            }
            failure {
                /* slackSend(
                    channel: '#test-ci',
                    color: 'danger',
                    tokenCredentialId: 'slack-token',
                    message: "❌ *${params.COMPONENT ?: component}* ${params.ENVIRONMENT} deploy failed — commit `${shortCommit ?: env.GIT_COMMIT.take(7)}` (<${env.BUILD_URL}console|console>)"
                ) */
                echo "failure"
            }
        }
    }
}
