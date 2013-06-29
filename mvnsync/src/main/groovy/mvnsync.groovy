//--------------------------------------------------------------------------
// Imports
//--------------------------------------------------------------------------
import org.apache.lucene.document.Document;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.search.BooleanClause.Occur;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.maven.index.ArtifactInfo;
import org.apache.maven.index.ArtifactInfoFilter;
import org.apache.maven.index.ArtifactInfoGroup;
import org.apache.maven.index.Field;
import org.apache.maven.index.FlatSearchRequest;
import org.apache.maven.index.FlatSearchResponse;
import org.apache.maven.index.GroupedSearchRequest;
import org.apache.maven.index.GroupedSearchResponse;
import org.apache.maven.index.Grouping;
import org.apache.maven.index.Indexer;
import org.apache.maven.index.IteratorSearchRequest;
import org.apache.maven.index.IteratorSearchResponse;
import org.apache.maven.index.MAVEN;
import org.apache.maven.index.context.IndexCreator;
import org.apache.maven.index.context.IndexUtils;
import org.apache.maven.index.context.IndexingContext;
import org.apache.maven.index.expr.SourcedSearchExpression;
import org.apache.maven.index.expr.UserInputSearchExpression;
import org.apache.maven.index.search.grouping.GAGrouping;
import org.apache.maven.index.updater.IndexUpdateRequest;
import org.apache.maven.index.updater.IndexUpdateResult;
import org.apache.maven.index.updater.IndexUpdater;
import org.apache.maven.index.updater.ResourceFetcher;
import org.apache.maven.index.updater.WagonHelper;
import org.apache.maven.wagon.Wagon;
import org.apache.maven.wagon.events.TransferEvent;
import org.apache.maven.wagon.events.TransferListener;
import org.apache.maven.wagon.observers.AbstractTransferListener;
import org.codehaus.plexus.DefaultPlexusContainer;
import org.codehaus.plexus.PlexusContainer;
import org.codehaus.plexus.PlexusContainerException;
import org.codehaus.plexus.component.repository.exception.ComponentLookupException;
import org.codehaus.plexus.util.StringUtils;
import org.sonatype.aether.util.version.GenericVersionScheme;
import org.sonatype.aether.version.InvalidVersionSpecificationException;
import org.sonatype.aether.version.Version;
//--------------------------------------------------------------------------

//--------------------------------------------------------------------------
// Default Settings
//--------------------------------------------------------------------------
remoteIndex = "http://mirrors.ibiblio.org/maven2/"
remoteRepository = "http://repo.maven.apache.org/maven2"
localIndex = "./index"	 			// Store local index in current directory
localRepository = "./repository"	// Check here when synchronising
maxDownload = -1     // -1 will download all required dependencies
batch = 10           // Number of downloads to batch together
delay = 0            // Delay in milliseconds between each batch

//--------------------------------------------------------------------------
// Define and parse the program arguments
//--------------------------------------------------------------------------
	
def cli = new CliBuilder(usage:'mvnsync [options]', header:'options:')

cli.h( longOpt: 'help', required: false, 'show usage information' )
cli.c( longOpt: 'classpath', required: false, 'print runtime classpath and exit' )
cli.m( longOpt: 'max', argName: 'int', required: false,args: 1, 'maximum downloads to perform' )
cli.b( longOpt: 'batch', argName: 'int', required: false, args: 1, 'download in specified batches' )
cli.d( longOpt: 'delay', argName: 'milliseconds', required: false, args: 1, 'millisecond delay between batches' )
cli.i( longOpt: 'remote-index', argName: 'url', required: false, args: 1, 'index download URL' )
cli.I( longOpt: 'local-index', argName: 'path', required: false, args: 1, 'download remote index here' )
cli.r( longOpt: 'remote-repository', argName: 'url', required: false, args: 1, 'artifact download URL' )
cli.l( longOpt: 'local-repository', argName: 'path', required: false, args: 1, 'sync with this local repository' )
cli.s( longOpt: 'sync', required: false, 'perform sync' )
cli.u( longOpt: 'update', required: false, 'incrementally update index before sync' )

// If nothing has been supplied at all then exit with usage
// even if -t or --test was specified
if( args == null || args.length == 0 ) {
	println "\n[error] No arguments supplied.\n"
	cli.usage()
	System.exit(-1)
}

// If here then something has been supplied so parse the passed arguments
def opt = cli.parse(args)

// If help requested then just give usage and exit
if(!opt){
	// If opt builder errored for any reason then exit here
	System.exit(-1)
}

// If help requested then just give usage and exit
if(opt.h){
	cli.usage()
	System.exit(0)
}

// Print classpath if specified
if(opt.c){
	println "\nRuntime Classpath:\n\n"
	printClassPath this.class.classLoader
	System.exit(0)
}

// If opt.arguments contains anything here then unknown options were supplied so exit with usage
if (opt.arguments()){
	println "\n[error] Invalid option(s) or argument(s)\n"
	cli.usage()
	System.exit(-1)
}

// If opt.arguments contains anything here then unknown options were supplied so exit with usage
if (!(opt.s || opt.u)){
	println "\n[error] Must specify at least one of: --sync --update\n"
	cli.usage()
	System.exit(-1)
}

//--------------------------------------------------------------------------
// Validate user supplied options
//--------------------------------------------------------------------------
if(opt.i){
	if(!validUrl(opt.i)){
		println "\n[error] -i " + opt.i + " is not a valid URL\n"
		cli.usage()
		System.exit(-1)
	}
}

if(opt.r){
	if(!validUrl(opt.r)){
		println "\n[error] -r " + opt.r + " is not a valid URL\n"
		cli.usage()
		System.exit(-1)
	}
}

if(opt.m){
	if(!opt.m.isNumber()){
		println "\n[error] -m " + opt.m + " is not a valid number\n"
		cli.usage()
		System.exit(-1)
	}
}

if(opt.d){
	if(!opt.d.isNumber()){
		println "\n[error] -d " + opt.d + " is not a valid number\n"
		cli.usage()
		System.exit(-1)
	}
}

if(opt.b){
	if(!opt.b.isNumber()){
		println "\n[error] -b " + opt.b + " is not a valid number\n"
		cli.usage()
		System.exit(-1)
	}
}
//--------------------------------------------------------------------------
// Initialise variables - don't modify these
//--------------------------------------------------------------------------
PlexusContainer plexusContainer
Indexer indexer
IndexUpdater indexUpdater
Wagon httpWagon
IndexingContext mavenContext
plexusContainer = new DefaultPlexusContainer()
indexer = plexusContainer.lookup( Indexer.class )
indexUpdater = plexusContainer.lookup( IndexUpdater.class )
httpWagon = plexusContainer.lookup( Wagon.class, "http" )

// Check if user overrode defaults
remoteIndex = (opt.i) ? opt.i.toString() : remoteIndex
remoteRepository = (opt.r) ? opt.r.toString() : remoteRepository
localIndex = (opt.I) ? opt.I.toString() : localIndex
localRepository = (opt.l) ? opt.l.toString() : localRepository
maxDownload = (opt.m) ? opt.m.toInteger() : maxDownload
delay = (opt.d) ? opt.d.toInteger() : delay
batch = (opt.b) ? opt.b.toInteger() : batch

// Set default maven command ( assume linux )
cmdExec = "mvn"

//--------------------------------------------------------------------------
// Start synchronising
//--------------------------------------------------------------------------
println "Starting synchronisation"
println "=============================================================="
	
// Set the cmd executable based on platform
if (System.properties['os.name'].toLowerCase().contains('windows')) {
	println "[Platform] windows"
	println "[cmd] cmd /c mvn"
	cmdExec = "cmd"
} else {
	println "[Platform] linux"
}
  
// Files where local cache is (if any) and Lucene Index should be located
File mavenLocalCache = new File( "$localIndex/maven-cache" )
File mavenIndexDir = new File( "$localIndex/maven-index" )

 // Creators we want to use (search for fields it defines)
List<IndexCreator> indexers = new ArrayList<IndexCreator>()
indexers.add( plexusContainer.lookup( IndexCreator.class, "min" ) )
indexers.add( plexusContainer.lookup( IndexCreator.class, "jarContent" ) )
indexers.add( plexusContainer.lookup( IndexCreator.class, "maven-plugin" ) )
  
// Create context for central repository index
mavenContext = indexer.createIndexingContext( 
	"maven-context", "maven",
	mavenLocalCache, mavenIndexDir,"$remoteIndex",
	null, true, true, indexers
)

//--------------------------------------------------------------------------
// If update requested, then update our local index from remote, before continuing
// note: incremental update will happen if this is not 1st run and files are not deleted
//--------------------------------------------------------------------------
if (opt.u){
	if(!opt.i){
		println("[warn] remote index not specified - skipping update")
		return
	}
	
	// If here then proceed with index update
	println( "Updating $localIndex from $remoteIndex")
	println( "This might take a while on first run, so please be patient!" );
	println( "==============================================================" )
 
	// Create ResourceFetcher implementation to be used with IndexUpdateRequest
	TransferListener listener = new AbstractTransferListener(){
		public void transferStarted( TransferEvent transferEvent ){
		   print( "  Downloading " + transferEvent.getResource().getName() );
		}

		public void transferProgress( TransferEvent transferEvent, byte[] buffer, int length ){}

		public void transferCompleted( TransferEvent transferEvent ){
			println( " - Done" );
		}
	};
	ResourceFetcher resourceFetcher = new WagonHelper.WagonFetcher( httpWagon, listener, null, null );

	Date mavenContextCurrentTimestamp = mavenContext.getTimestamp();
	IndexUpdateRequest updateRequest = new IndexUpdateRequest( mavenContext, resourceFetcher );
	IndexUpdateResult updateResult
	try{
		updateResult = indexUpdater.fetchAndUpdateIndex( updateRequest );
	}catch(e){
		println "\n[error] could not fetch index. Check URL\n"
		System.exit(-1)
	}
	if ( updateResult.isFullUpdate() )
	{
		println( "Full update happened!" );
	}
	else if ( updateResult.getTimestamp().equals( mavenContextCurrentTimestamp ) )
	{
		println( "No update needed, index is up to date!" );
	}
	else
	{
		println( "Incremental update happened, change covered " + mavenContextCurrentTimestamp
			+ " - " + updateResult.getTimestamp() + " period." );
	}

}

//--------------------------------------------------------------------------
// Now scan the entire downloaded index and download artifacts that are not found locally
// or until max-downloads reached. Apply a delay every blocksize if required
//--------------------------------------------------------------------------
if (opt.s){
	println()
	println( "Searching $localIndex for new artifacts" )
	println( "==============================================================" )
	
	IndexSearcher searcher = mavenContext.acquireIndexSearcher()
	ant = new AntBuilder()	 // Call maven from built in antRunner
	downloaded = 0           // max download counter
	batchCurrent = 0         // batch counter
	
	try{
		def IndexReader ir = searcher.getIndexReader();
		maxDownload = (maxDownload > -1 && ir.maxDoc() > maxDownload) ? maxDownload : ir.maxDoc()
		println()
		println "Found " + ir.maxDoc() + " artifacts in the index"
		println "Downloading a maximum of " + maxDownload.toString()
		println( "==============================================================" )
		
		// Loop through every artifact in index
		for ( int i = 0; i < ir.maxDoc(); i++ ){
			if ( !ir.isDeleted( i ) ){
				// Get the index document to search for
				Document doc = ir.document( i );
				
				// Get its artifact information
				ArtifactInfo ai = IndexUtils.constructArtifactInfo( doc, mavenContext );
							
				// Artifact info might be null, so check to be sure
				if( ai != null ){
					// Dont bother trying to download poms directly, just content
					if( ai.fextension != "pom" ){
						// If classifier load the string ready
						classifier = (ai.classifier != null) ? "-" + ai.classifier : ""
						
						// See if file exists locally
						localFileCheck = new File("$localRepository/"
							+ ai.groupId.replace(".","/") + "/" + ai.artifactId + "/" + ai.version
							+ "/" + ai.artifactId + "-" + ai.version
							+ classifier
							+ "." + ai.fextension)
						
						if( !localFileCheck.exists()){
							
							// See if it has a pom and if it doesn whether the artifact has been relocated
							relocated = false
							pomCheck = new File("$localRepository/"
								+ ai.groupId.replace(".","/") + "/" + ai.artifactId + "/" + ai.version
								+ "/" + ai.artifactId + "-" + ai.version
								+ classifier
								+ ".pom")
							
							if(pomCheck.exists()){
								pomCheckXML = new XmlSlurper().parseText(pomCheck.getText())
								pomCheckXML.depthFirst().any {
									if(it.name()=="relocation"){
										relocated = true
										println "Skipping: [" + ai.groupId + ":" + ai.artifactId + ":" + ai.version	+ "], relocated to: " + it.children()[0]
									}
								}
							}
							if(relocated == false){
								// If here, then we found an artifact but it hasn't been downloaded yet, or was deleted
								// and it isn't listed as relocated
								print "Downloading: [" + localFileCheck.getPath() +"] "
			
								// fetch individual artifact version via standard nexus setup
								ant.exec( executable:cmdExec){
									// Platform dependent
									if(cmdExec.contains('cmd')){
										arg(value: "/c")
										arg(value:"mvn")
									}							
									
									arg(value:"--quiet")
									//arg(value:"--batch-mode")
									arg(value:"org.apache.maven.plugins:maven-dependency-plugin:2.7:get")
									arg(value:"-DremoteRepositories=" + remoteRepository)
									
									artifact = ai.groupId+":"+ai.artifactId+":"+ai.version
									
									if(ai.packaging != null){
										artifact = artifact + ":" + ai.packaging
									}
									
									if(ai.classifier != null){
										artifact = artifact + ":" + ai.classifier
									}
									
									arg(value:"-Dartifact="+artifact)
								}
								
								if( localFileCheck.exists() ){
									println "- success"
								}else{
									println "- failed"
								}
								
								// Return if we have reached our chosen max
								if( maxDownload != 0 && ++downloaded >= maxDownload ){
									println "MaxDownload limit reached"
									return
								}
								
								// If we've hit a batch limit then sleep for chosen amount
								if(  ++batchCurrent > 0 && batchCurrent == batch ){
									if( delay > 0 ){
										println "sleeping for $delay milliseconds"
										sleep delay
									}
									batchCurrent = 0
								}
							}
						}
					}
				}
			}
		}
	}
	finally{
		mavenContext.releaseIndexSearcher( searcher );
		println( "==============================================================" )
		println( "Finished" )
	}
}


def printClassPath(classLoader) {
	println "$classLoader"
	classLoader.getURLs().each {url->
	   println "- ${url.toString()}"
	}
	if (classLoader.parent) {
	   printClassPath(classLoader.parent)
	}
}

def validUrl(url){
	try {
		new URL(url.toString())
		return true
	} catch (MalformedURLException e) {
		return false
	}
}
