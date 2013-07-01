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
import org.codehaus.plexus.util.FileUtils;
//--------------------------------------------------------------------------

//--------------------------------------------------------------------------
// Default Settings
//--------------------------------------------------------------------------
remoteRepositoryUrl = "http://repo.maven.apache.org/maven2"
localIndexPath = "./index"	 			// Store local index in current directory
localRepositoryPath = "./repository"	// Check here when synchronising
maxDownload = -1     // -1 will download all required dependencies

//--------------------------------------------------------------------------
// Define and parse the program arguments
//--------------------------------------------------------------------------
	
def cli = new CliBuilder(usage:'mvnsync [options]', header:'options:')

cli.h( longOpt: 'help', required: false, 'show usage information' )
cli.m( longOpt: 'max', argName: 'int', required: false,args: 1, 'maximum downloads to perform' )
cli.i( longOpt: 'index', argName: 'path', required: false, args: 1, 'download remote index here' )
cli.r( longOpt: 'remote', argName: 'url', required: false, args: 1, 'remote artifact repository' )
cli.l( longOpt: 'local', argName: 'path', required: false, args: 1, 'local artifact repository' )
cli.s( longOpt: 'sync', required: false, 'perform sync' )
cli.u( longOpt: 'update', required: false, 'incrementally update index before sync' )

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

// Must specify at least --sync or --update
if (!(opt.s || opt.u)){
	println "\n[error] Must specify at least one of: --sync --update\n"
	cli.usage()
	System.exit(-1)
}

//--------------------------------------------------------------------------
// Validate user supplied options
//--------------------------------------------------------------------------
if(opt.m){
	if(!opt.m.isNumber()){
		println "\n[error] -m " + opt.m + " is not a valid number\n"
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

// Make sure --max-downloads is a real number if any
if(opt.m){
	if(!opt.m.isNumber()){
		println "\n[error] -m " + opt.m + " is not a valid number\n"
		cli.usage()
		System.exit(-1)
	}
}

remoteRepositoryUrl = (opt.r) ? opt.r.toString() : remoteRepositoryUrl
localRepositoryPath = (opt.l) ? opt.l.toString() : localRepositoryPath
localIndexPath = (opt.i) ? opt.i.toString() : localIndexPath
maxDownload = (opt.m) ? opt.m.toInteger() : maxDownload

//--------------------------------------------------------------------------
// Display runtime settings
//--------------------------------------------------------------------------
println "Starting"
println "=============================================================="
println "Remote Repository: $remoteRepositoryUrl"
println "Local Repository: $localRepositoryPath"
println "Local Index: $localIndexPath"
println "Download threshold: $maxDownload"
println "=============================================================="

//--------------------------------------------------------------------------
// Path validation
//--------------------------------------------------------------------------
try{
	new URL( remoteRepositoryUrl )
}catch ( MalformedURLException e ){
	println "[error] malformed remote repository url"
	System.exit(-1)
}

localRepository = new File(localRepositoryPath)
if(opt.s && !localRepository.exists()){
	println "[error] Local repository doesnt exist"
	System.exit(-1)
}

localIndex = new File(localIndexPath)
if(!localIndex.exists()){
	println "[error] Local index directory doesnt exist"
	System.exit(-1)
}

//--------------------------------------------------------------------------
// Configure maven indexer
//--------------------------------------------------------------------------
// Files where local cache is (if any) and Lucene Index should be located
File mavenLocalCache = new File( localIndex.getPath(), "maven-cache" )
File mavenIndexDir = new File( localIndex.getPath(), "maven-index" )

 // Creators we want to use (search for fields it defines)
List<IndexCreator> indexers = new ArrayList<IndexCreator>()
indexers.add( plexusContainer.lookup( IndexCreator.class, "min" ) )
indexers.add( plexusContainer.lookup( IndexCreator.class, "jarContent" ) )
indexers.add( plexusContainer.lookup( IndexCreator.class, "maven-plugin" ) )
  
// Create context for repository index
mavenContext = indexer.createIndexingContext( 
	"maven-context", "maven",
	mavenLocalCache, mavenIndexDir,remoteRepositoryUrl,
	null, true, true, indexers
)

//--------------------------------------------------------------------------
// If update requested, then update our local index from remote, before continuing
// note: incremental update will happen if this is not 1st run and files are not deleted
//--------------------------------------------------------------------------
if (opt.u){
	if(!opt.r){
		println("[warn] remote repository not specified - skipping update")
		return
	}
	
	// If here then proceed with index update
	println( "[update] Updating " + localIndex.getPath() + " from $remoteRepositoryUrl")
	println( "[update] This might take a while on first run, so please be patient!" );
	 
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
		println "[error] could not fetch remote index: " + e.message
		System.exit(-1)
	}
	if ( updateResult.isFullUpdate() )
	{
		println( "[update] Full update happened!" );
	}
	else if ( updateResult.getTimestamp().equals( mavenContextCurrentTimestamp ) )
	{
		println( "[update] No update needed, index is up to date!" );
	}
	else
	{
		println( "[update] Incremental update happened, change covered " + mavenContextCurrentTimestamp
			+ " - " + updateResult.getTimestamp() + " period." );
	}

}

//--------------------------------------------------------------------------
// Scan the index and download artifacts that are not found locally
// or until max-downloads reached.
//--------------------------------------------------------------------------
if (opt.s){
	// Acquire index searcher
	IndexSearcher searcher = mavenContext.acquireIndexSearcher()
	downloaded = 0           // max download counter
	
	try{
		IndexReader ir = searcher.getIndexReader();
		maxDownload = (maxDownload > -1 && ir.maxDoc() > maxDownload) ? maxDownload : ir.maxDoc()
		println "[sync] Found total of " + ir.maxDoc() + " artifacts in the index"
		
		// Loop through every artifact in index
		for ( int i = 0; i < ir.maxDoc(); i++ ){
			// Don't bother with deleted artifacts
			if ( !ir.isDeleted( i ) ){
				// Get the indexed document to search for
				Document doc = ir.document( i );
				
				// Pull out the artifact information
				ArtifactInfo ai = IndexUtils.constructArtifactInfo( doc, mavenContext );
											
				// Artifact info might be null, so check to be sure
				if( ai != null ){
					// Parse remote file path
					String relPath = path(ai)
					// Get local file
					localFile = new File( localRepository, relPath )
										
					// Try direct download if the artifact doesn't already exist
					if( !localFile.exists()){
						print "[download] " + path(ai)
						try{
							FileUtils.copyURLToFile(new URL(remoteRepositoryUrl), localFile)
							println " - done"
						}catch ( e ){
							if ( !localFile.exists() ){
								println " - not found"
							}else{
								println " - error transfering"
							}
							println e.message
						}
						
						// If reached maxdoc then end here
						if( ++downloaded >= maxDownload){
							println "[download] max downloads reached"
							return
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

// Build relative path from remote artifact
def path( ArtifactInfo ai ){
	StringBuilder path = new StringBuilder( 128 );
	path.append( ai.groupId.replace( '.', '/' ) ).append( '/' );
	path.append( ai.artifactId ).append( '/' );
	path.append( ai.version ).append( '/' );
	path.append( ai.artifactId ).append( '-' ).append( ai.version );
	if ( ai.classifier != null && ai.classifier.length() > 0 ){
		path.append( '-' ).append( ai.classifier );
	}
	path.append( '.' ).append( ai.fextension );
	return path.toString();
}
