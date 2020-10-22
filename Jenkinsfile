config { }

node() {
	git.checkout { }
	
	catchError {
		maven { }
	}

	reportIssues()

	notify { }
}
