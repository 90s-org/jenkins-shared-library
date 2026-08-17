def call (Map configMap){
    pipeline {
        agent { 
            node { 
                label 'ROBOSHOP' 
            } 
        }
        environment {
            def appVersion = ""
            acc_id = "160885265516"
            project = configMap.get("project")
            component = configMap.get("component")
            org = "90s-org"
        }
        options {
            disableConcurrentBuilds()
            timeout(time: 15, unit: 'MINUTES')
        }
        /* parameters {
            string(name: 'PERSON', defaultValue: 'Mr Jenkins', description: 'Who should I say hello to?')
            text(name: 'BIOGRAPHY', defaultValue: '', description: 'Enter some information about the person')
            booleanParam(name: 'DEPLOY', defaultValue: true, description: 'Toggle this value')
            choice(name: 'CHOICE', choices: ['One', 'Two', 'Three'], description: 'Pick something')
            password(name: 'PASSWORD', defaultValue: 'SECRET', description: 'Enter a password')
        } */
        // Build
        stages {
            stage('read-version'){
                steps{
                    script {
                        def packageJson = readJSON file: 'package.json'
                        // Extract the version property
                        appVersion = packageJson.version
                        echo "The application version is: ${appVersion}"
                    }
                }
            }
            stage('install-dependencies') {
                steps {
                    script {
                        sh """
                            npm install
                        """
                    } 
                }
            }
            // this command gives us coverage report and test cases report, sonarqube access this to check quality gate
            stage('unit-tests') {
                steps {
                    script {
                        try {
                            sh """
                                npm test
                            """
                            utils.updateCommitStatus('SUCCESS', 'Unit tests passed', 'unit-tests')
                        } catch (Exception e) {
                            utils.updateCommitStatus('FAILURE', 'Unit tests failed', 'unit-tests')
                            throw e
                        }
                    } 
                }
            }
            stage('sonar-analysis') {
                steps {
                    // 'My SonarQube Server' must match the name configured in Jenkins System Settings
                    withSonarQubeEnv('sonar-server') {
                        sh "${tool 'sonar-8'}/bin/sonar-scanner"
                    }
                }
            }
            stage('sonar-scan') {
                steps {
                    timeout(time: 10, unit: 'MINUTES') {
                        script {
                            def qg = waitForQualityGate() // Pauses pipeline
                            if (qg.status != 'OK') {
                                utils.updateCommitStatus('FAILURE', 'Sonar Scan failed', 'sonar-scan')
                                error "Pipeline aborted: ${qg.status}"
                            }
                            else {
                                utils.updateCommitStatus('success', 'Sonar Scan success', 'sonar-scan')
                            }
                        }
                    }
                }
            }
            stage('library-scan') {
                steps {
                    script {
                        try{
                            withCredentials([string(credentialsId: 'github-token', variable: 'GH_TOKEN')]) {
                                sh '''
                                    set -e

                                    REPO="${org}/${component}"

                                    curl -s -L \
                                    -H "Accept: application/vnd.github+json" \
                                    -H "Authorization: Bearer ${GH_TOKEN}" \
                                    -H "X-GitHub-Api-Version: 2026-03-10" \
                                    "https://api.github.com/repos/${REPO}/dependabot/alerts?state=open" \
                                    -o alerts.json

                                    echo "---- Open Dependabot Alerts ----"
                                    jq -r '.[] | "\\(.number)\\t\\(.security_vulnerability.severity)\\t\\(.dependency.package.name)\\t\\(.security_advisory.ghsa_id)"' alerts.json

                                    HIGH_CRITICAL_COUNT=$(jq '[.[] | select(.security_vulnerability.severity == "high" or .security_vulnerability.severity == "critical")] | length' alerts.json)

                                    echo "High/Critical alert count: ${HIGH_CRITICAL_COUNT}"

                                    if [ "$HIGH_CRITICAL_COUNT" -gt 0 ]; then
                                        echo "❌ Found ${HIGH_CRITICAL_COUNT} High/Critical severity dependency alert(s). Failing build."
                                        exit 1
                                    else
                                        echo "✅ No High/Critical dependency alerts found."
                                    fi
                                '''
                            }
                            utils.updateCommitStatus('SUCCESS', 'Library scan passed', 'library-scan')
                        }
                        catch (Exception e){
                            utils.updateCommitStatus('FAILURE', 'Library scan failed', 'library-scan')
                            throw e
                        }
                    }
                }
            }
            stage('build-image') {
                steps {
                    script {
                        // in this block we get aws authentication
                        try {
                            withAWS(credentials: 'aws-creds', region: 'us-east-1') {
                                sh """
                                    aws ecr get-login-password --region us-east-1 | docker login --username AWS --password-stdin ${acc_id}.dkr.ecr.us-east-1.amazonaws.com
                                    docker build -t ${acc_id}.dkr.ecr.us-east-1.amazonaws.com/${project}/${component}:${appVersion} .
                                """
                            }
                            utils.updateCommitStatus('success', 'Docker image build', 'build-image')
                        }
                        catch (Exception e) {
                            utils.updateCommitStatus('failure', 'Docker image faied', 'build-image')
                            throw e
                        }
                        
                    }
                }
            }
            stage('trivy-scan') {
                steps {
                    script {
                        def osScan = sh(script: """
                            trivy image --scanners vuln --pkg-types os \
                            --severity HIGH,CRITICAL --exit-code 1 \
                            --format table --output trivy-os-report.txt \
                            160885265516.dkr.ecr.us-east-1.amazonaws.com/${project}/${component}:${appVersion}
                        """, returnStatus: true)

                        def dockerfileScan = sh(script: """
                            trivy config --severity HIGH,CRITICAL --exit-code 1 \
                            --format table --output trivy-dockerfile-report.txt \
                            Dockerfile
                        """, returnStatus: true)

                        archiveArtifacts artifacts: 'trivy-*.txt', allowEmptyArchive: true

                        if (osScan != 0 || dockerfileScan != 0) {
                            utils.updateCommitStatus('failure', 'trivy scan failed', 'trivy-scan')
                            error("Trivy found HIGH/CRITICAL issues — OS scan exit: ${osScan}, Dockerfile scan exit: ${dockerfileScan}")
                        }
                        utils.updateCommitStatus('success', 'trivy scan success', 'trivy-scan')
                    }
                }
            }
            stage('push-image-to-ecr'){
                steps{
                    script{
                        try {
                            withAWS(credentials: 'aws-creds', region: 'us-east-1') {
                                sh """
                                docker push ${acc_id}.dkr.ecr.us-east-1.amazonaws.com/${project}/${component}:${appVersion}
                                utils.updateCommitStatus('success', 'push image to ECR', 'push-image')
                                """
                            }
                        }
                        catch(Exception e){
                            utils.updateCommitStatus('failure', 'push image to ECR', 'push-image')
                            throw e
                        }
                    }
                }
            }
            stage('Deploy') {
                when {
                    // Evaluates the boolean parameter directly
                    expression { "${params.DEPLOY}" == "true" }
                }
                /* input {
                    message "Should we continue?"
                    ok "Yes, we should."
                    submitter "alice,bob"
                    parameters {
                        string(name: 'PERSON', defaultValue: 'Mr Jenkins', description: 'Who should I say hello to?')
                    }
                } */
                steps {
                    script {
                        sh """
                            echo "Deploying"
                        """
                    }
                }
            }
        }

        post { 
            always { 
                echo 'I will always say Hello again!'
            }
            success { 
                echo 'I will run when success'
            }
            failure { 
                echo 'I will Run when it is failed'
            }
        }
    }
}