#!groovy

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

// this script is taken from olamy works on archiva-jenkins-lib for the Apache Archiva project
def call(Map params = [:]) {
    // variable needed for apidoc
    def myAnt = ""
    def apidocurl = ""
    def date  = ""
    def atomdate = ""
    def jdktool = ""
    def myMaven=""
    def version=""
    def rmversion=""
    def mavenVersion=""
    def month=""
    pipeline {
        options {
            buildDiscarder(logRotator(numToKeepStr: '2'))
            disableConcurrentBuilds() 
        }
        agent { node { label 'ubuntu' } }
        stages{
            stage("Preparing Variable"){
                agent { node { label 'ubuntu' } }
                options { timeout(time: 180, unit: 'MINUTES') }
                steps{
                    script {
                        // test if we can do that 
                        sh 'curl "https://gitbox.apache.org/repos/asf?p=netbeans-jenkins-lib.git;a=blob_plain;f=meta/netbeansrelease.json" -o netbeansrelease.json'
                        def releaseInformation = readJSON file: 'netbeansrelease.json'
                        sh 'rm -f netbeansrelease.json'
                        def branch = env.BRANCH_NAME 
                        def githash = env.GIT_COMMIT
                        
                        println githash
                        println branch
                        
                        if (!releaseInformation[branch]) {
                            // no branch definined in json exit build
                            currentBuild.result = "FAILURE"
                            throw new Exception("No entry in json for $branch")
                        }
                        myAnt = releaseInformation[branch].ant;
                        apidocurl = releaseInformation[branch].apidocurl
                        mavenVersion=releaseInformation[branch].mavenversion
                        
                        switch (releaseInformation[branch].releasedate['month']) {
                        case '01':month  = 'Jan'; break;
                        case '02':month  = 'Feb'; break;
                        case '03':month  = 'Mar'; break;
                        case '04':month  = 'Apr'; break;
                        case '05':month  = 'May'; break;
                        case '06':month  = 'Jun'; break;
                        case '07':month  = 'Jul'; break;
                        case '08':month  = 'Aug'; break;
                        case '09':month  = 'Sep'; break;
                        case '10':month  = 'Oct'; break;
                        case '11':month  = 'Nov'; break;
                        case '12':month  = 'Dec'; break;
                        default: month ='Invalid';
                        }
                        date  = releaseInformation[branch].releasedate['day'] + ' '+ month + ' '+releaseInformation[branch].releasedate['year']
                        //2018-07-29T12:00:00Z
                        atomdate = releaseInformation[branch].releasedate['year']+'-'+releaseInformation[branch].releasedate['month']+'-'+releaseInformation[branch].releasedate['day']+'T12:00:00Z'
                        jdktool = releaseInformation[branch].jdk
                        myMaven = releaseInformation[branch].maven
                        version = releaseInformation[branch].versionName;
                        
                        rmversion = version
                        //
                        if (releaseInformation[branch].milestones) {
                            releaseInformation[branch].milestones.each{key,value ->
                                if (key==githash) {
                                    // vote candidate prior
                                    if (value['vote']) {
                                        rmversion = rmversion+'-vc'+value['vote']
                                    } else if (value['version']){
                                        // other named version
                                        rmversion = rmversion+'-'+value['version']
                                    }
                                }                             
                            }
                        } 
                    }
                }
            }
            stage ("Main build") {
                tools {
                    jdk jdktool
                }
                steps {
                    withAnt(installation: myAnt) {
                        script {
                            //sh 'ant'
                            if (env.BRANCH_NAME=="master") {
                                // on master we build apidoc + populating snapshot repository
                                // should be on line for each otherwise cluster are wrong
                                sh "ant build-nbms"
                                sh "ant build-source-zips"
                                sh "ant build-javadoc -Djavadoc.web.zip=${env.WORKSPACE}/WEBZIP.zip"
                                sh "rm -rf ${env.WORKSPACE}/repoindex/"
                                sh "rm -rf ${env.WORKSPACE}/.repository"
                                def localRepo = "${env.WORKSPACE}/.repository"
                                withMaven(maven:myMaven,jdk:jdktool,publisherStrategy: 'EXPLICIT',mavenLocalRepo: localRepo)
                                {
                                    //sh "mvn org.apache.maven.plugins:maven-dependency-plugin:3.1.1:get -Dartifact=org.apache.netbeans.utilities:nb-repository-plugin:1.5-SNAPSHOT -DremoteRepositories=apache.snapshots.https::::https://repository.apache.org/snapshots"
                                    sh "mvn org.apache.netbeans.utilities:nb-repository-plugin:1.5:download -DnexusIndexDirectory=${env.WORKSPACE}/repoindex -DrepositoryUrl=https://repo.maven.apache.org/maven2"
                                    sh "mvn org.apache.netbeans.utilities:nb-repository-plugin:1.5:populate -DnexusIndexDirectory=${env.WORKSPACE}/repoindex -DnetbeansNbmDirectory=${env.WORKSPACE}/nbbuild/nbms -DnetbeansInstallDirectory=${env.WORKSPACE}/nbbuild/netbeans -DnetbeansSourcesDirectory=${env.WORKSPACE}/nbbuild/build/source-zips -DnetbeansJavadocDirectory=${env.WORKSPACE}/nbbuild/build/javadoc -DparentGAV=org.apache.netbeans:netbeans-parent:2 -DforcedVersion=${mavenVersion} -DskipInstall=true -DdeployId=apache.snapshots.https -DdeployUrl=https://repository.apache.org/content/repositories/snapshots"
                                }
                                
                            } else if (month !='Invalid') {
                                // we have a valid month, this package is already released. Build only javadoc
                                sh "ant"
                                sh "ant build-javadoc -Djavadoc.web.zip=${env.WORKSPACE}/WEBZIP.zip"
                            } else {
                                // we want to setup for release
                                // apidoc + repomaven + dist bundle
                                def clusterconfigs = ['platform','release']
                                def targets = ['verify-libs-and-licenses','rat','build']
                                sh "rm -rf ${env.WORKSPACE}/nbbuild/build"
                                
                                for (String clusterconfig in clusterconfigs) {
                                    // force a build num for build-source-config
                                    sh "ant build-source-config -Dcluster.config=${clusterconfig} -Dbuildnum=666"
                                    for (String target in targets){
                                        sh "rm -rf ${env.WORKSPACE}/${target}-${clusterconfig}-temp"
                                        sh "mkdir  ${env.WORKSPACE}/${target}-${clusterconfig}-temp"
                                        sh "unzip ${env.WORKSPACE}/nbbuild/build/${clusterconfig}*.zip -d ${env.WORKSPACE}/${target}-${clusterconfig}-temp "
                                        sh "cp ${env.WORKSPACE}/.gitignore ${env.WORKSPACE}/${target}-${clusterconfig}-temp"
                                        def add = "";
                                        // 
                                        if (target=="build" && env.BRANCH_NAME!="release90") {
                                            add=" -Ddo.build.windows.launchers=true"
                                        }
                                        sh "ant -f ${env.WORKSPACE}/${target}-${clusterconfig}-temp/build.xml ${target} -Dcluster.config=${clusterconfig} ${add}"
                                    }
                                    
                                }
                                                               
                                
                                sh "ant -f ${env.WORKSPACE}/build-release-temp/build.xml build-nbms build-source-zips generate-uc-catalog -Dcluster.config=release -Ddo.build.windows.launchers=true"
                                sh "ant -f ${env.WORKSPACE}/build-release-temp/build.xml build-javadoc -Djavadoc.web.root='${apidocurl}' -Dmodules-javadoc-date='${date}' -Datom-date='${atomdate}' -Djavadoc.web.zip=${env.WORKSPACE}/WEBZIP.zip"
                               
                                // remove folders
                                sh "rm -rf ${env.WORKSPACE}/dist"
                                sh "rm -rf ${env.WORKSPACE}/mavenrepository"
                                
                                // create dist folder and content
                                sh "mkdir ${env.WORKSPACE}/dist"
                                sh "cp ${env.WORKSPACE}/nbbuild/build/*platform*.zip ${env.WORKSPACE}/dist/netbeans-platform-${rmversion}-source.zip"
                                sh "cp ${env.WORKSPACE}/nbbuild/build/release*.zip ${env.WORKSPACE}/dist/netbeans-${rmversion}-source.zip"
                                sh "cp ${env.WORKSPACE}/build-platform-temp/nbbuild/*.zip ${env.WORKSPACE}/dist/netbeans-platform-${rmversion}-bin.zip"
                                sh "cp ${env.WORKSPACE}/build-release-temp/nbbuild/*-release.zip ${env.WORKSPACE}/dist/netbeans-${rmversion}-bin.zip"
                                sh "mkdir ${env.WORKSPACE}/dist/nbms"
                                 
                                // creat maven repository folder and content
                                sh "mkdir ${env.WORKSPACE}/mavenrepository"
                                sh "cp -r ${env.WORKSPACE}/build-release-temp/nbbuild/nbms/** ${env.WORKSPACE}/dist/nbms/"
                                sh "cd ${env.WORKSPACE}/dist"+' && for z in $(find . -name "*.zip") ; do sha512sum $z >$z.sha512 ; done'
                                sh "cd ${env.WORKSPACE}/dist"+' && for z in $(find . -name "*.nbm") ; do sha512sum $z >$z.sha512 ; done'
                                sh "cd ${env.WORKSPACE}/dist"+' && for z in $(find . -name "*.gz") ; do sha512sum $z >$z.sha512 ; done'
                                
                                archiveArtifacts 'dist/**'
                                
                                //prepare a maven repository to be used by RM 
                                sh "rm -rf ${env.WORKSPACE}/repoindex/"
                                sh "rm -rf ${env.WORKSPACE}/.repository"
                                def localRepo = "${env.WORKSPACE}/.repository"
                                def netbeansbase = "${env.WORKSPACE}/build-release-temp/nbbuild"
                                withMaven(maven:myMaven,jdk:jdktool,publisherStrategy: 'EXPLICIT',mavenLocalRepo: localRepo,options:[artifactsPublisher(disabled: true)])
                                {
                                    //sh "mvn org.apache.maven.plugins:maven-dependency-plugin:3.1.1:get -Dartifact=org.apache.netbeans.utilities:nb-repository-plugin:1.5-SNAPSHOT -Dmaven.repo.local=${env.WORKSPACE}/.repository -DremoteRepositories=apache.snapshots.https::::https://repository.apache.org/snapshots"
                                    sh "mvn org.apache.netbeans.utilities:nb-repository-plugin:1.5:download -DnexusIndexDirectory=${env.WORKSPACE}/repoindex -Dmaven.repo.local=${env.WORKSPACE}/.repository -DrepositoryUrl=https://repo.maven.apache.org/maven2"
                                    sh "mvn org.apache.netbeans.utilities:nb-repository-plugin:1.5:populate -DnexusIndexDirectory=${env.WORKSPACE}/repoindex -Dmaven.repo.local=${env.WORKSPACE}/.repository -DnetbeansNbmDirectory=${netbeansbase}/nbms -DnetbeansInstallDirectory=${netbeansbase}/netbeans -DnetbeansSourcesDirectory=${netbeansbase}/build/source-zips -DnetbeansJavadocDirectory=${netbeansbase}/build/javadoc -DparentGAV=org.apache.netbeans:netbeans-parent:2 -DforcedVersion=${mavenVersion} -DskipInstall=true -DdeployUrl=file://${env.WORKSPACE}/mavenrepository"
                                }                            
                                archiveArtifacts 'mavenrepository/**'     
                            }
                        }                       
                    }
                    archiveArtifacts 'WEBZIP.zip'
                    
                }
            }
        }
        post {
            cleanup {
                cleanWs() // deleteDirs: true, notFailBuild: true, patterns: [[pattern: '**/.repository/**', type: 'INCLUDE']]
            }
            success {
                slackSend (channel:'#netbeans-builds', message:"SUCCESS: Job '${env.JOB_NAME} [${env.BUILD_NUMBER}]' (${env.BUILD_URL}) ",color:'#00FF00')
            }
            failure {
                slackSend (channel:'#netbeans-builds', message:"FAILED: Job '${env.JOB_NAME} [${env.BUILD_NUMBER}]'  (${env.BUILD_URL})",color:'#FF0000')
            }
            
        }
    }
}
