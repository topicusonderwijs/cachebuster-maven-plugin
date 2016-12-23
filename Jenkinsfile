#!groovy

job.configuration { }

node(){
	stage('checkout'){
		checkout scm
	}
	
	stage('maven build'){
		maven.execute { }
	}
}
