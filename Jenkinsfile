config { }

node() {
	git.checkout { }
	
	catchError {
		maven { }
	}

	publishTestReports { }

	notify { }
}
