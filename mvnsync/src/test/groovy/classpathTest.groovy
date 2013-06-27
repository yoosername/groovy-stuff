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

def printClassPath(classLoader) {
	println "$classLoader"
	classLoader.getURLs().each {url->
	   println "- ${url.toString()}"
	}
	if (classLoader.parent) {
	   printClassPath(classLoader.parent)
	}
  }

printClassPath this.class.classLoader
