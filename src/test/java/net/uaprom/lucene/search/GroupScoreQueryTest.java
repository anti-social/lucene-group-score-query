package net.uaprom.lucene.search;

import java.io.IOException;

import org.apache.lucene.index.Term;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.RandomIndexWriter;
import org.apache.lucene.store.Directory;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.IntField;
import org.apache.lucene.document.Document;
import org.apache.lucene.util.LuceneTestCase;


public class GroupScoreQueryTest extends LuceneTestCase {
    public void test() throws IOException {
        Directory dir = newDirectory();
        RandomIndexWriter writer = new RandomIndexWriter(random(), dir);

        Document doc;
        doc = new Document();
        doc.add(new IntField("company_id", 1, Field.Store.NO));
        doc.add(newTextField("name", "Test name", Field.Store.NO));
        writer.addDocument(doc);
        doc = new Document();
        doc.add(new IntField("company_id", 1, Field.Store.NO));
        doc.add(newTextField("name", "Super name", Field.Store.NO));
        writer.addDocument(doc);
        doc = new Document();
        doc.add(new IntField("company_id", 2, Field.Store.NO));
        doc.add(newTextField("name", "Super-puper name", Field.Store.NO));
        writer.addDocument(doc);

        DirectoryReader reader = writer.getReader();
        writer.close();

        final IndexSearcher searcher = newSearcher(reader);

        Query q = new TermQuery(new Term("name", "name"));
        assertEquals(3, searcher.search(q, 1).totalHits);

        reader.close();
        dir.close();
    }
}
