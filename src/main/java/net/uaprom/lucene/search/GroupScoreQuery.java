package net.uaprom.lucene.search;

import java.io.IOException;

import org.apache.lucene.search.Query;
import org.apache.lucene.search.Weight;
import org.apache.lucene.search.IndexSearcher;


public class GroupScoreQuery extends Query {
    public Weight createWeight(IndexSearcher searcher) throws IOException {
        return null;
    }

    @Override
    public String toString(String field) {
        return "";
    }
}
