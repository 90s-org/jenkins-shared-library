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
        }
        environment {
            def appVersion = ""
            def shortCommit = ""
            acc_id = "160885265516"
            project = configMap.get("project")
            component = configMap.get("component")
            org = "90s-org"
        }
        options {
            disableConcurrentBuilds()
            timeout(time: 15, unit: 'MINUTES')
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
                                    aws eks update-kubeconfig --name roboshop-dev --region us-east-1

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
            // Dev-deploy and api-tests passed against this merge commit — promote the
            // same image by retagging it with the short commit SHA in ECR.
            stage('promote-image') {
                when {
                    expression { params.ENVIRONMENT == 'dev' }
                }
                steps {
                    script {
                        try {
                            shortCommit = env.GIT_COMMIT.take(7)
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
            // Manual promotion path: pick ENVIRONMENT (sit/uat/prod), give the commit
            // that was already verified in dev, and the target component/project.
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
                        utils.validateCommitStatus(params.COMMIT_ID.trim(), ['dev-deploy', 'api-tests'])
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
                                    aws eks update-kubeconfig --name roboshop-dev --region us-east-1

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
                    echo "UAT deploy — not implemented yet"
                }
            }
            stage('prod-deploy') {
                when {
                    expression { params.ENVIRONMENT == 'prod' }
                }
                steps {
                    echo "PROD deploy — not implemented yet"
                }
            }
        }

        post {
            success {
                slackSend(
                    channel: '#test-ci',
                    color: 'good',
                    tokenCredentialId: 'slack-token',
                    message: "✅ *${params.COMPONENT ?: component}* ${params.ENVIRONMENT} deploy succeeded — commit `${shortCommit ?: env.GIT_COMMIT.take(7)}` (<${env.BUILD_URL}console|console>)"
                )
            }
            failure {
                slackSend(
                    channel: '#test-ci',
                    color: 'danger',
                    tokenCredentialId: 'slack-token',
                    message: "❌ *${params.COMPONENT ?: component}* ${params.ENVIRONMENT} deploy failed — commit `${shortCommit ?: env.GIT_COMMIT.take(7)}` (<${env.BUILD_URL}console|console>)"
                )
            }
        }
    }
}
