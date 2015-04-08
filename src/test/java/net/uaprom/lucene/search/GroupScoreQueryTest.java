package net.uaprom.lucene.search;

import java.io.IOException;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.IntField;
import org.apache.lucene.expressions.Expression;
import org.apache.lucene.expressions.SimpleBindings;
import org.apache.lucene.expressions.js.JavascriptCompiler;
import org.apache.lucene.index.AtomicReader;
import org.apache.lucene.index.AtomicReaderContext;
import org.apache.lucene.index.BinaryDocValues;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.index.RandomIndexWriter;
import org.apache.lucene.index.Term;
import org.apache.lucene.queries.function.FunctionValues;
import org.apache.lucene.queries.function.ValueSource;
import org.apache.lucene.queries.function.docvalues.FloatDocValues;
import org.apache.lucene.search.Explanation;
import org.apache.lucene.search.FieldCache;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.Rescorer;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.SortField;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.Directory;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.LuceneTestCase;


public class GroupScoreQueryTest extends LuceneTestCase {
    public void test() throws IOException, ParseException {
        Map<String, String> map = new HashMap<String, String>();
        String a = "123";
        
        Directory dir = newDirectory();
        RandomIndexWriter writer = new RandomIndexWriter(random(), dir);

        Document doc;
        doc = new Document();
        doc.add(new IntField("company_id", 1, Field.Store.NO));
        doc.add(newTextField("name", "Test name name", Field.Store.NO));
        writer.addDocument(doc);
        doc = new Document();
        doc.add(new IntField("company_id", 1, Field.Store.NO));
        doc.add(newTextField("name", "Super name name name", Field.Store.NO));
        writer.addDocument(doc);
        doc = new Document();
        doc.add(new IntField("company_id", 2, Field.Store.NO));
        doc.add(newTextField("name", "Super-puper name", Field.Store.NO));
        writer.addDocument(doc);
        doc = new Document();
        doc.add(new IntField("company_id", 2, Field.Store.NO));
        doc.add(newTextField("name", "Super-mega name name", Field.Store.NO));
        writer.addDocument(doc);
        writer.commit();
        // write to a new segment
        doc = new Document();
        doc.add(new IntField("company_id", 2, Field.Store.NO));
        doc.add(newTextField("name", "Super-mega name in new segment", Field.Store.NO));
        writer.addDocument(doc);
        doc = new Document();
        doc.add(new IntField("company_id", 1, Field.Store.NO));
        doc.add(newTextField("name", "Simple test name", Field.Store.NO));
        writer.addDocument(doc);
        doc = new Document();
        doc.add(new IntField("company_id", 3, Field.Store.NO));
        doc.add(newTextField("name", "Test name", Field.Store.NO));
        writer.addDocument(doc);

        DirectoryReader reader = writer.getReader();
        writer.close();

        final IndexSearcher searcher = newSearcher(reader);

        Query q = new TermQuery(new Term("name", "name"));
        TopDocs hits = searcher.search(q, 100);
        for (ScoreDoc hit : hits.scoreDocs) {
            System.out.println(hit.doc);
            System.out.println(hit.score);
        }
        System.out.println();

        Expression expr = JavascriptCompiler.compile("1 / (_pos + 1)");
        SimpleBindings bindings = new SimpleBindings();
        // bindings.add(new SortField("_score", SortField.Type.SCORE));
        bindings.add("_pos", new GroupPositionValueSource());
        Rescorer rescorer = new GroupPosRescorer("company_id",
                                                 expr.getValueSource(bindings));
        TopDocs rescoredHits = rescorer.rescore(searcher, hits, 5);
        for (ScoreDoc rHit : rescoredHits.scoreDocs) {
            System.out.println(rHit.doc);
            System.out.println(rHit.score);
        }
        
        reader.close();
        dir.close();
    }

    class GroupPosRescorer extends Rescorer {
        private String groupField;
        private ValueSource valueSource;

        public GroupPosRescorer(String groupField, ValueSource valueSource) {
            this.groupField = groupField;
            this.valueSource = valueSource;
        }

        @Override
        public TopDocs rescore(IndexSearcher searcher, TopDocs firstPassTopDocs, int topN) throws IOException {
            ScoreDoc[] hits = firstPassTopDocs.scoreDocs.clone();
            Arrays.sort(hits,
                        new Comparator<ScoreDoc>() {
                            @Override
                            public int compare(ScoreDoc a, ScoreDoc b) {
                                return a.doc - b.doc;
                            }
                        });

            List<AtomicReaderContext> readerContexts = searcher.getIndexReader().leaves();
            Iterator<AtomicReaderContext> readerContextIterator = readerContexts.iterator();
            AtomicReaderContext currentReaderContext = readerContextIterator.next();
            // BinaryDocValues binaryValues = getBinaryValues(currentReaderContext);
            // System.out.println(binaryValues);
            FieldCache.Ints intValues = getIntValues(currentReaderContext);

            // Map<BytesRef,List<ScoreDoc>> groupedDocs = new HashMap<BytesRef,List<ScoreDoc>>();
            Map<Integer,List<ScoreDoc>> groupedDocs = new HashMap<Integer,List<ScoreDoc>>();
            Map<Integer,AtomicReaderContext> docLeafContexts = new HashMap<Integer,AtomicReaderContext>();

            for (ScoreDoc hit : hits) {
                System.out.println(hit.doc);

                AtomicReaderContext prevReaderContext = currentReaderContext;
                
                // find segment with current document
                int relativeDocID = hit.doc - currentReaderContext.docBase;
                while (relativeDocID >= currentReaderContext.reader().maxDoc()) {
                    currentReaderContext = readerContextIterator.next();
                    relativeDocID = hit.doc - currentReaderContext.docBase;
                }

                docLeafContexts.put(hit.doc, currentReaderContext);

                // if (currentReaderContext != prevReaderContext) {
                //     binaryValues = getBinaryValues(currentReaderContext);
                // }
                if (currentReaderContext != prevReaderContext) {
                    intValues = getIntValues(currentReaderContext);
                }

                // BytesRef target = binaryValues.get(relativeDocID);
                // System.out.println(target);
                // if (!groupedDocs.containsKey(target)) {
                //     groupedDocs.put(target, new ArrayList<ScoreDoc>());
                // }
                // groupedDocs.get(target).add(hit);
                // System.out.println(groupedDocs.get(target).size());
                int val = intValues.get(relativeDocID);
                if (!groupedDocs.containsKey(val)) {
                    groupedDocs.put(val, new ArrayList<ScoreDoc>());
                }
                groupedDocs.get(val).add(hit);
            }
            System.out.println();

            Comparator<ScoreDoc> scoreComparator = new Comparator<ScoreDoc>() {
                @Override
                public int compare(ScoreDoc a, ScoreDoc b) {
                    if (a.score > b.score) {
                        return -1;
                    }
                    else if (a.score < b.score) {
                        return 1;
                    }
                    return a.doc - b.doc;
                }
            };

            ScoreDoc[] rescoredHits = new ScoreDoc[hits.length];
            Map<AtomicReaderContext,Map<Integer,Integer>> docPositions = new HashMap<AtomicReaderContext,Map<Integer,Integer>>();
            for (List<ScoreDoc> docs : groupedDocs.values()) {
                Collections.sort(docs, scoreComparator);

                int pos = 0;
                for (ScoreDoc doc : docs) {
                    AtomicReaderContext leafContext = docLeafContexts.get(doc.doc);
                    if (!docPositions.containsKey(leafContext)) {
                        docPositions.put(leafContext, new HashMap<Integer,Integer>());
                    }
                    docPositions.get(leafContext).put(doc.doc - leafContext.docBase, pos);
                    pos++;
                }
            }

            Map valueSourceContext = new HashMap();
            valueSourceContext.put("docPositions", docPositions);
            Map<AtomicReaderContext,FunctionValues> leafFunctionValues = new HashMap<AtomicReaderContext,FunctionValues>();
            for (AtomicReaderContext leafContext : readerContexts) {
                System.out.println(leafContext);
                System.out.println(valueSource);
                System.out.println(valueSource.getValues(valueSourceContext, leafContext));
                leafFunctionValues.put(leafContext,
                                       valueSource.getValues(valueSourceContext, leafContext));
            }

            int i = 0;
            for (List<ScoreDoc> docs : groupedDocs.values()) {
                for (ScoreDoc doc : docs) {
                    // valueSource.getValues(valueSourceContext, docReaderContexts.get(doc.doc))
                    // float newScore = doc.score / (pos + 1);
                    AtomicReaderContext leafContext = docLeafContexts.get(doc.doc);
                    float newScore = doc.score * leafFunctionValues.get(leafContext).floatVal(doc.doc - leafContext.docBase);
                    rescoredHits[i] = new ScoreDoc(doc.doc, newScore);
                    i++;
                }
            }

            Arrays.sort(rescoredHits, scoreComparator);

            if (topN < hits.length) {
                ScoreDoc[] subset = new ScoreDoc[topN];
                System.arraycopy(rescoredHits, 0, subset, 0, topN);
                rescoredHits = subset;
            }
            
            return new TopDocs(firstPassTopDocs.totalHits, rescoredHits, rescoredHits[0].score);
        }

        private BinaryDocValues getBinaryValues(AtomicReaderContext readerContext) throws IOException {
            AtomicReader reader = readerContext.reader();
            FieldInfo groupedFieldInfo = reader.getFieldInfos().fieldInfo(groupField);
            return FieldCache.DEFAULT.getTerms(reader, groupField, false);
        }

        private FieldCache.Ints getIntValues(AtomicReaderContext readerContext) throws IOException {
            AtomicReader reader = readerContext.reader();
            FieldInfo groupedFieldInfo = reader.getFieldInfos().fieldInfo(groupField);
            return FieldCache.DEFAULT.getInts(reader, groupField, false);
        }

        @Override
        public Explanation explain(IndexSearcher searcher, Explanation firstPassExplanation, int docID) throws IOException {
            return firstPassExplanation;
        }
    }

    class GroupPositionValueSource extends ValueSource {
        @Override
        public FunctionValues getValues(Map context, AtomicReaderContext readerContext) throws IOException {
            final Map<Integer,Integer> docPositions =
                ((Map<AtomicReaderContext,Map<Integer,Integer>>)context.get("docPositions")).get(readerContext);

            return new FloatDocValues(this) {
                @Override
                public float floatVal(int doc) {
                    return docPositions.get(doc);
                }
            };
        }

        @Override
        public boolean equals(Object o) {
            if (o.getClass() != GroupPositionValueSource.class) {
                return false;
            }

            return this == o;
        }

        @Override
        public int hashCode() {
            return 1;
        }

        @Override
        public String description() {
            return "_pos";
        }
    }
}
