//--------------------------------------------------------------------------
// Fetch required dependencies and import jars
//--------------------------------------------------------------------------
@Grab(group='org.apache.lucene', module='lucene-core', version='4.0.0')
@Grab(group='org.apache.lucene', module='lucene-queryparser', version='4.0.0')
@Grab(group='org.apache.lucene', module='lucene-analyzers-common', version='4.0.0')
@Grab(group='org.apache.lucene', module='lucene-queries', version='4.0.0')

@Grab(group='org.apache.maven.indexer', module='indexer-artifact', version='5.1.1')
@Grab(group='org.apache.maven.indexer', module='indexer-core', version='5.1.1')
@Grab(group='org.apache.maven.indexer', module='maven-indexer', version='5.1.1')
@Grab(group='org.apache.maven.indexer', module='indexer-cli', version='5.1.1')

@Grab(group='org.apache.maven.wagon', module='wagon-provider-api', version='2.4')
@Grab(group='org.apache.maven.wagon', module='wagon-http', version='2.4')

@Grab(group='org.codehaus.plexus', module='plexus', version='3.3.1')
@Grab(group='org.codehaus.plexus', module='plexus-utils', version='3.0.10')
@Grab(group='org.codehaus.plexus', module='plexus-classworlds', version='2.4.2')
@Grab(group='org.codehaus.plexus', module='plexus-container-default', version='1.5.5')

@Grab(group='org.sonatype.aether', module='aether-api', version='1.13.1')

@Grab(group='commons-logging', module='commons-logging', version='1.1.3')

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
// Define and parse the program arguments
//--------------------------------------------------------------------------
def cli = new CliBuilder(usage:'mvnsync [options]', header:'options:')

cli.h( longOpt: 'help', required: false, 'show usage information' )
cli.m( longOpt: 'max-download', argName: 'int', required: false,args: 1, 'restrict number of downloaded artifacts to number specified' )
cli.b( longOpt: 'batch', argName: 'number', required: false, args: 1, 'download dependencies in batches of specified amount' )
cli.d( longOpt: 'delay', argName: 'milliseconds', required: false, args: 1, 'amount of time to delay between each batch download. Default: 0 milliseconds' )
cli.n( longOpt: 'local-index-name', argName: 'name', required: true, args: 1, 'name to use for locally stored index' )
cli.r( longOpt: 'remote-index-url', argName: 'url', required: false, args: 1, 'remote repository url to fetch index from' )
cli.l( longOpt: 'local-repository-path', argName: 'path', required: true, args: 1, 'path to local folder used to compare index against' )
cli.R( longOpt: 'randomize', required: false, 'if selected downloads will be rencomised in the batch pom' )
cli.u( longOpt: 'update-index', required: false, 'if specified then the local index will be updated from remote before syncing' )

// If nothing has been supplied at all then exit with usage
// even if -t or --test was specified
if( args == null || args.length == 0 ) {
    println "[error] No arguments supplied.\n"
    cli.usage()
    return
}

// If here then something has been supplied so parse the passed arguments
def opt = cli.parse(args)

// If opt is null then we specified a required option but it was not supplied so exit with usage
if(!opt){
    // println "Missing required option(s).\n"
    //cli.usage()
    return
}

// If opt.arguments contains anything here then unknown options were supplied so exit with usage
if (opt.arguments()){
    println "[error] Invalid option(s) or argument(s).\n"
    cli.usage()
    return
}

//--------------------------------------------------------------------------
// Set some program wide variables
//--------------------------------------------------------------------------
PlexusContainer plexusContainer
Indexer indexer
IndexUpdater indexUpdater
Wagon httpWagon
IndexingContext centralContext
plexusContainer = new DefaultPlexusContainer()
indexer = plexusContainer.lookup( Indexer.class )
indexUpdater = plexusContainer.lookup( IndexUpdater.class )
httpWagon = plexusContainer.lookup( Wagon.class, "http" )
localIndexName = opt.n
localRepositoryPath = opt.l
remoteIndexUrl = opt.r    // e.g. http://mirrors.ibiblio.org/maven2/
  
// Files where local cache is (if any) and Lucene Index should be located   
File centralLocalCache = new File( "indexes/$localIndexName/maven-cache" );
File centralIndexDir = new File( "indexes/$localIndexName/maven-index" );

 // Creators we want to use (search for fields it defines)
List<IndexCreator> indexers = new ArrayList<IndexCreator>();
indexers.add( plexusContainer.lookup( IndexCreator.class, "min" ) );
indexers.add( plexusContainer.lookup( IndexCreator.class, "jarContent" ) );
indexers.add( plexusContainer.lookup( IndexCreator.class, "maven-plugin" ) );
  
// Create context for central repository index
centralContext = indexer.createIndexingContext( "$localIndexName-context", "$localIndexName", 
    centralLocalCache, centralIndexDir,"$remoteIndexUrl", 
    null, true, true, indexers );

//--------------------------------------------------------------------------
// If update requested, then update our local index from remote, before continuing
// note: incremental update will happen if this is not 1st run and files are not deleted
//--------------------------------------------------------------------------
if (opt.u){
    if(!opt.r){
        println("No remote repository specified, skipping update")
        return
    }
    println( "Updating $localIndexName at " + centralIndexDir.getPath() + " from $remoteIndexUrl")
    println( "This might take a while on first run, so please be patient!" );
    println( "==============================================================" )
 
    // Create ResourceFetcher implementation to be used with IndexUpdateRequest
    // Here, we use Wagon based one as shorthand, but all we need is a ResourceFetcher implementation
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

    Date centralContextCurrentTimestamp = centralContext.getTimestamp();
    IndexUpdateRequest updateRequest = new IndexUpdateRequest( centralContext, resourceFetcher );
    IndexUpdateResult updateResult = indexUpdater.fetchAndUpdateIndex( updateRequest );
    if ( updateResult.isFullUpdate() )
    {
        println( "Full update happened!" );
    }
    else if ( updateResult.getTimestamp().equals( centralContextCurrentTimestamp ) )
    {
        println( "No update needed, index is up to date!" );
    }
    else
    {
        println( "Incremental update happened, change covered " + centralContextCurrentTimestamp
            + " - " + updateResult.getTimestamp() + " period." );
    }

}

//--------------------------------------------------------------------------
// Now scan the entire downloaded index and download artifacts that are not found locally
// or until max-downloads reached. Apply a delay every blocksize if required
//--------------------------------------------------------------------------
println()
println( "Searching $localIndexName index at " + centralIndexDir.getPath())
println( "==============================================================" )

def IndexSearcher searcher = centralContext.acquireIndexSearcher()
def maxDownload = 0    // Default:0 is to download all deps that are found
def delay = 0    // Seconds between each mvn build
def batch = 10    // Perform builds in batches of 10
def randomize = false
localFileCheck = null
ant = new AntBuilder()
downloaded = 0       // Used to determine how many artifacts we have actually requested
batchCurrent = 0     // Batch counter

if( opt.m ){
    maxDownload = opt.m.toInteger()
}

if( opt.d ){
    delay = opt.d.toInteger()
}

if( opt.b ){
    batch = opt.b.toInteger()
}

if( opt.R ){
    randomize = true
}

try{
    def IndexReader ir = searcher.getIndexReader();
    maxDownload = (maxDownload > 0 && ir.maxDoc() > maxDownload) ? maxDownload : ir.maxDoc()
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
            ArtifactInfo ai = IndexUtils.constructArtifactInfo( doc, centralContext );
                        
            // Artifact info might be null, so check to be sure
            if( ai != null ){
                // Dont bother trying to download poms directly, just content
                if( ai.fextension != "pom" ){
                    // If classifier load the string ready
                    classifier = (ai.classifier != null) ? "-" + ai.classifier : ""
                    
                    // See if file exists locally
                    localFileCheck = new File("$localRepositoryPath/" 
                        + ai.groupId.replace(".","/") + "/" + ai.artifactId + "/" + ai.version
                        + "/" + ai.artifactId + "-" + ai.version 
                        + classifier                 
                        + "." + ai.fextension)
                    
                    if( !localFileCheck.exists() ){
                        // If here, then we found an artifact but it hasnt been downloaded yet, or was deleted
                        // or we chose the wrong or unused local repository
                        print "Adding: [" + localFileCheck.getPath() +"] "
    
                        // fetch individual artifact version via standard nexus setup
                        ant.exec( executable:"cmd"){
                            arg(value: "/c")
                            arg(value:"mvn")
                            //arg(value:"-e")
                            arg(value:"--quiet")
                            arg(value:"org.apache.maven.plugins:maven-dependency-plugin:2.7:get")
                            
                            artifact = ai.groupId+":"+ai.artifactId+":"+ai.version
                            
                            if(ai.packaging != null){
                                artifact = artifact + ":" + ai.packaging
                            }
                            
                            if(ai.classifier != null){
                                artifact = artifact + ":" + ai.classifier
                            }
                            
                            arg(value:"-Dartifact="+artifact)
                        }
                        
                        println "- done"
                        
                        // Return if we have reached our chosen max
                        if( maxDownload != 0 && ++downloaded >= maxDownload ){
                            println( "==============================================================" )
                            println "MaxDownload limit reached, end here"
                            return
                        }
                        
                        // If weve hit a batch limit then sleep for chosen amount
                        if(  ++batchCurrent > 0 && batchCurrent == batch ){
                            if( delay > 0 ){
                                println "sleeping for $delay milliseconds"
                                sleep delay
                            }
                            batchCurrent = 0
                        }
                        
                    }
                }
                //println( ai.groupId + ":" + ai.artifactId + ":" + ai.version + ":" + ai.classifier + " (sha1=" + ai.sha1 + ")" );
            }
        }
    }
}
finally{
    centralContext.releaseIndexSearcher( searcher );
}