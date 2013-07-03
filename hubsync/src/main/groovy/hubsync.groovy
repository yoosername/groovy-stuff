import groovy.json.*

// Small cli for a few options	
def cli = new CliBuilder(usage:'hubsync [options]', header:'options:')

cli.h( longOpt: 'help', required: false, 'show usage information' )
cli.l( longOpt: 'list', required: false, 'list available repos' )
cli.n( longOpt: 'new', required: false, 'optionally show only new repos' )
cli.c( longOpt: 'config', argName: "file", required: false, args: 1, 'specify config file' )
cli.cl( longOpt: 'clone', required: false, 'clone available repos' )

// If nothing has been supplied at all then exit with usage
if( args == null || args.length == 0 ) {
	println "\n[error] No arguments supplied.\n"
	cli.usage()
	System.exit(-1)
}

// If here then something has been supplied so parse the passed arguments
def opt = cli.parse(args)

// If opt builder had error exit here
if(!opt){
	System.exit(-1)
}

// If help requested then just give usage and exit
if(opt.h){
	cli.usage()
	System.exit(0)
}

// If opt.arguments contains anything here then unknown options were supplied so exit with usage
if (opt.arguments()){
	println "\n[error] Invalid option(s) or argument(s)\n"
	cli.usage()
	System.exit(-1)
}

// Must specify at least --list or --clone
if (!(opt.l || opt.cl )){
	println "\n[error] Must specify at least one of: --list --clone\n"
	cli.usage()
	System.exit(-1)
}

//--------------------------------------------------------------------------
// Load config file
//--------------------------------------------------------------------------
configFile = (opt.c)? new File(opt.c) : new File('config.groovy')
if(!configFile.exists()){
	println "[error] config file doesnt exist: ${configFile.getPath()}"
	System.exit(-1)
}
def config = new ConfigSlurper().parse(configFile.toURL())

//--------------------------------------------------------------------------
// Main
//--------------------------------------------------------------------------
// List available repos
if(opt.l){
	if(config.users && (!"".equals(config.users))){
		config.users.split(",").each { user -> 
			def url = "https://api.github.com/users/$user/repos".toURL().text
			def root = new JsonSlurper().parseText(url)
			root.each() { repo ->
				if(opt.n){
					local = new File(repo.name)
					if(!local.exists()){
						println "${repo.html_url}"
					}
				}else{
					println "${repo.html_url}"
				}
			}
		}
	}else{
		println "[error] config.users doesnt contain anything"
	}
}

// Clone available repos
if(opt.cl){
	if(config.users && (!"".equals(config.users))){
		config.users.split(",").each { user ->
			def url = "https://api.github.com/users/$user/repos".toURL().text
			def root = new JsonSlurper().parseText(url)
			root.each() { repo ->
				local = new File(repo.name)
				if(!local.exists()){
					print "[clone] ${repo.html_url}"
					"git clone ${repo.html_url} ${repo.name}".execute().waitFor()
					println " - done"
				}else{
					print "[update] ${repo.html_url}"
					"git pull ${repo.html_url} ${repo.name}".execute().waitFor()
					println " - done"
				}
			}
		}
	}else{
		println "[error] config.users doesnt contain anything"
	}
}