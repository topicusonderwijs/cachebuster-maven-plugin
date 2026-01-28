def isPrimary = env.BRANCH_IS_PRIMARY
def isReleaseBuild = env.TAG_NAME && env.TAG_NAME.startsWith('v')

config { }

node() {
	git.checkout { }
	
	catchError {
		maven { }
	}

	reportIssues()

	def notifyConfig = isPrimary || isReleaseBuild ? { slackChannel = "#dev-cobra-notifications" } : {}
	notify notifyConfig
}
