package com.redislabs.sa.ot.jllmvss;

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.search.Document;
import redis.clients.jedis.search.Query;

import java.util.List;
import java.util.Map;

import static com.redislabs.sa.ot.jllmvss.ByteArrayHelper.longArrayToByteArray;

public class VectorSearchExample {
    static TimeSeriesEventLogger eventLogger = new TimeSeriesEventLogger().
            setTSKeyNameForMyLog("VectorSearchExample:EmbeddingEvent").
            setCustomLabel("countingTokensUsed");
    static TimeSeriesEventLogger durationLogger = new TimeSeriesEventLogger().
            setTSKeyNameForMyLog("VectorSearchExample:EmbeddingEventDuration").
            setCustomLabel("durationInMilliseconds");

    public static void main(String[] args){
        JedisPooled jedis = new JedisPooledGetter(args).getJedisPooled();
        eventLogger.setJedis(jedis).initTS();
        durationLogger.setJedis(jedis).initTS();

        long startTime = System.currentTimeMillis(); // not including connection setup time
        //create the sentence to compare to the searchable embeddings:
        String searchText = "The trip taken was through India for this popular animal";
        System.out.println("\nSEARCH RESULT from comparing this sentence:\n\t"+searchText);
        long beforeTime = System.currentTimeMillis();
        executeVSSQuery(searchText,jedis);
        durationLogger.addEventToMyTSKey(System.currentTimeMillis()-beforeTime);
        eventLogger.addEventToMyTSKey(searchText.length()/4);

        searchText = "Something about a hat trick";
        System.out.println("\nSEARCH RESULT from comparing this sentence:\n\t"+searchText);
        beforeTime = System.currentTimeMillis();
        executeVSSQuery(searchText,jedis);
        durationLogger.addEventToMyTSKey(System.currentTimeMillis()-beforeTime);
        eventLogger.addEventToMyTSKey(searchText.length()/4);

        searchText = "Dangerous - do not approach";
        System.out.println("\nSEARCH RESULT from comparing this sentence:\n\t"+searchText);
        beforeTime = System.currentTimeMillis();
        executeVSSQuery(searchText,jedis);
        durationLogger.addEventToMyTSKey(System.currentTimeMillis()-beforeTime);
        eventLogger.addEventToMyTSKey(searchText.length()/4);

        searchText = "The Gorilla that wasn't feeling well.";
        System.out.println("\nSEARCH RESULT from comparing this sentence:\n\t"+searchText);
        beforeTime = System.currentTimeMillis();
        executeVSSQuery(searchText,jedis);
        durationLogger.addEventToMyTSKey(System.currentTimeMillis()-beforeTime);
        eventLogger.addEventToMyTSKey(searchText.length()/4);

        System.out.println("\n\t\tTIME in MILLISECONDS TAKEN TO EXECUTE Queries in VectorSearchExample - "+(System.currentTimeMillis()-startTime));
    }

    static List<Document> executeVSSQuery(String searchText,JedisPooled jedis){
        // Create the embedding model
        Map<String, String> options = Map.of("maxLength", "768",  "modelMaxLength", "768");
        HuggingFaceTokenizer sentenceTokenizer = HuggingFaceTokenizer.newInstance("sentence-transformers/all-mpnet-base-v2", options);
        int K = 3;
        Query q = new Query("*=>[KNN $K @embedding $BLOB AS score]").
                setSortBy("score",false).
                returnFields("biography", "score").
                addParam("K", K).
                addParam("BLOB", longArrayToByteArray(sentenceTokenizer.encode(searchText).getIds())).
                limit(0,1).
                dialect(2);

        // Execute the query
        List<Document> docs = jedis.ftSearch("idx_zoo", q).getDocuments();
        for(Document d:docs){
            System.out.println("\n\t"+d);
        }
        return docs;
    }
}
