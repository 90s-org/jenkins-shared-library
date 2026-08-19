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
                        }
                        catch (Exception e) {
                            utils.updateCommitStatus('failure', 'roboshop-integration-tests failed', 'sit-integration-tests')
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
                        }
                        catch (Exception e) {
                            utils.updateCommitStatus('failure', 'roboshop-regression-tests failed', 'uat-regression-tests')
                            throw e
                        }
                    }
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
