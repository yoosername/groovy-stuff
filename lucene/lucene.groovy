@Grab(group='org.apache.lucene', module='lucene-core', version='4.0.0')
@Grab(group='org.apache.lucene', module='lucene-queryparser', version='4.0.0')
@Grab(group='org.apache.lucene', module='lucene-analyzers-common', version='4.0.0')
@Grab(group='org.apache.lucene', module='lucene-queries', version='4.0.0')

import org.apache.lucene.analysis.*
import org.apache.lucene.analysis.standard.*
import org.apache.lucene.document.*
import org.apache.lucene.index.*
import org.apache.lucene.queryparser.classic.*
import org.apache.lucene.search.*
import org.apache.lucene.store.*
import org.apache.lucene.util.*

// This script searches the index for a term which has been passed in as a command line argument.

// Main program
assertArgumentsPassed()
luceneIndex = new LuceneIndex(this.args[0])
searchIndexForTerm(this.args[1])

// Helper Methods
void assertArgumentsPassed(){
    assert args.length == 2 : "Usage: groovy ${this.getClass().getName()}.groovy <index> <searchTerm>"
}

void searchIndexForTerm(String term) {
    def matches = [];
    def duration = benchmark( { matches = luceneIndex.searchFor(term) } )
    println "Completed search of index in ${duration} ms found ${matches.size()} matches."
    matches.each {println it}
}

int benchmark(Closure closure) {
    def start = System.currentTimeMillis()
    closure.call()
    def now = System.currentTimeMillis()
    return now - start
}

class LuceneIndex {
    def maxSearchMatches = 100000
    def indexDirectory
    def analyzer = new StandardAnalyzer(Version.LUCENE_40)
    
    def LuceneIndex(String directory){
        indexDirectory = FSDirectory.open(new File(directory))
    }

    List<String> searchFor(String searchTerm) {
        def indexReader = DirectoryReader.open(indexDirectory);
        def query = new QueryParser(Version.LUCENE_40, "content", analyzer).parse(searchTerm);
        def indexSearcher = new IndexSearcher(indexReader);
        def hits =  indexSearcher.search(query, maxSearchMatches).scoreDocs;
        def matchingStrings = hits.collect{indexSearcher.doc(it.doc).content}
        indexReader.close();
        return matchingStrings
    }
}