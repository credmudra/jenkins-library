def call(boolean deploy,int port,String volume){
    pipeline {
        agent any
        tools {
            maven 'M2_HOME'
            jdk 'JAVA_HOME'
        }
        stages {
            
            /* Stage #1 - Build Project */
            stage("Set Environments"){
                steps{
                    echo 'Setting up environments'
                    script {
                        if (env.BRANCH_NAME =~ /^(develop|(feature|(bug|hot)fix)(\/[a-zA-Z0-9]+([-_][a-zA-Z0-9]+)*){1,2}|\/[0-9]+(\.[0-9]+)*(-(alpha|beta|rc)[0-9]*)?)$/) {
                            sh 'cp '+"$CREDMUDRA_ENV_BASE_DIRECTORY"+'/dev/.env .'
                        }
                        if (env.BRANCH_NAME == 'uat') {
                            sh 'cp '+"$CREDMUDRA_ENV_BASE_DIRECTORY"+'/uat/.env .'
                        }
                         if (env.BRANCH_NAME == 'master') {
                            sh 'cp '+"$CREDMUDRA_ENV_BASE_DIRECTORY"+'/prod/.env .'
                        }
                    }
                }
            }

            /* Stage #2 - Build Project */
            stage("Application Build"){
                steps{
                    echo 'Building project with maven command'
                    sh 'mvn clean install -DskipTests'
                }
            }

            /* stage #2 Secrets Detection */
            /*stage("Security Audit"){
                steps{
                    sh "docker run -v ${WORKSPACE}:/src --workdir /src returntocorp/semgrep-agent:v1 semgrep-agent --config p/ci --config p/security-audit --config p/secrets"
                }
            }*/

            /* Stage #3 -Building docker image and deploy to docker container using docker-compose*/
            stage("Docker build & push"){
                steps{
                    script {
                        echo 'Building docker image & pushing to docker registry'
                        def allJob = env.JOB_NAME.tokenize('/') as String[];
                        def baseName = allJob[0];
                        if(baseName != 'credmudra' )
                           baseName='credmudra'; 
                        def projectName = allJob[allJob.length-2];
                        
                        sh 'docker build --build-arg VERSION='+"$BUILD_NUMBER"+' --build-arg IMAGE='+"$projectName"+' -t '+"$projectName:$BUILD_NUMBER"+' .'
                        sh 'docker tag '+"$projectName:$BUILD_NUMBER"+' '+"$baseName/$projectName"
                        sh 'docker login --username '+"$CREDMUDRA_DOCKER_USERNAME"+'  --password '+"$CREDMUDRA_DOCKER_PASSWORD"
                        sh 'docker push '+"$baseName/$projectName"
                        sh 'docker logout'
                    }
                }
            }

            /* Stage #4 -Deploying application*/
            stage("Deploy application"){
                when{
                    expression {
                        return deploy
                    }
                }
                steps{
                    echo 'Deploying docker image to server'
                    script{
                        def allJob = env.JOB_NAME.tokenize('/') as String[];
                        def baseName = allJob[0];
                        if(baseName != 'credmudra' )
                           baseName='credmudra';
                        def projectName = allJob[allJob.length-2];

                        if (env.BRANCH_NAME =~ /^(develop|(feature|(bug|hot)fix)(\/[a-zA-Z0-9]+([-_][a-zA-Z0-9]+)*){1,2}|\/[0-9]+(\.[0-9]+)*(-(alpha|beta|rc)[0-9]*)?)$/) {

                            sh 'docker ps -q --filter name='+"$projectName"+' | xargs -r docker stop'
                            sh 'docker ps -aq --filter name='+"$projectName"+' | xargs -r docker rm'
                            sh 'docker run -d --network=cred-servers -p '+"$port"+':80 '+  "$volume"+ ' --env-file=.env --name '+"$projectName $baseName/$projectName"
                        }

                        if (env.BRANCH_NAME == 'master') {

                                sshagent(credentials: ['credmudra-prod-gateway-private-key']) {
                                        sh 'scp -o StrictHostKeyChecking=no .env root@'+"$CREDMUDRA_PROD_SERVER_IP"+':/root'
                                        sh 'ssh -o StrictHostKeyChecking=no root@'+"$CREDMUDRA_PROD_SERVER_IP"+' docker ps -q --filter \\"name='+"$projectName"+'\\" \\| xargs -r docker stop'
                                        sh 'ssh -o StrictHostKeyChecking=no root@'+"$CREDMUDRA_PROD_SERVER_IP"+' docker ps -aq --filter \\"name='+"$projectName"+'\\" \\| xargs -r docker rm'
                                        sh 'ssh -o StrictHostKeyChecking=no root@'+"$CREDMUDRA_PROD_SERVER_IP"+' docker images -af reference=\\"'+"$baseName/$projectName"+'\\" -q \\| xargs -r docker rmi'
                                        sh 'ssh -o StrictHostKeyChecking=no root@'+"$CREDMUDRA_PROD_SERVER_IP"+' docker pull '+"$baseName/$projectName"
                                        sh 'ssh -o StrictHostKeyChecking=no root@'+"$CREDMUDRA_PROD_SERVER_IP"+' docker run -d --network=\\"cred-servers\\" -p '+"$port"+':80 '+"$volume"+' --env-file=.env --name '+"$projectName $baseName/$projectName"
                                }
                        }
                    }

                }
            }
        }

        post{
            always {
                cleanWs()
            }
            success{
                echo 'pipeline successfully executed'
                emailext (
                        subject: "SUCCESSFUL: Job '${env.JOB_NAME} [${env.BUILD_NUMBER}]'",
                        body: """<p>SUCCESSFUL: Job '${env.JOB_NAME} [${env.BUILD_NUMBER}]':</p>
                             <p>Check console output at &QUOT;<a href='${env.BUILD_URL}'>${env.JOB_NAME} [${env.BUILD_NUMBER}]</a>&QUOT;</p>""",
                        recipientProviders: [[$class: 'DevelopersRecipientProvider']]
                )
            }
            failure{
                echo 'pipeline failed'
            }
        
        }
    }

}
