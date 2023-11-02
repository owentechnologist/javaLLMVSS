package com.redislabs.sa.ot.jllmvss;

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.apache.poi.ss.formula.eval.NotImplementedException;
import redis.clients.jedis.JedisPooled;

import java.util.Map;
import java.util.Scanner;

import static com.redislabs.sa.ot.jllmvss.ByteArrayHelper.longArrayToByteArray;
import static java.time.Duration.ofSeconds;

//TODO: populate the hash with vector embedding byte[]  (createEmbedding() method provided)
//jedis.hset(cachedLLMExchangeKeyName.getBytes(), "embedding".getBytes(), createEmbedding(text));
//TODO: ensure appropriate search index exists (create it, or throw exception if not)
//TODO: implement query logic FT.SEARCH (see VectorSearchExample.java for reference)

/**
 * To make this go you would use a command like this:
 *  mvn compile exec:java -Dexec.cleanupDaemonThreads=false -Dexec.args="-h redis-12000.homelab.local -p 12000 -s p@ssword333 -vsssemanticcachecomplete"
 **/

public class VSSSemanticCachedLLMExchangeComplete {
    HuggingFaceTokenizer sentenceTokenizer = null;
    static TimeSeriesEventLogger eventLogger = new TimeSeriesEventLogger().
            setTSKeyNameForMyLog("VSSSemanticCache:GetAResponseEvent").
            setCustomLabel("countingTokensUsed");
    static TopKEntryLogger topKEntryLogger = new TopKEntryLogger();//use default topkKeyName
    static JedisPooled jedis = null;
    static Scanner in = new Scanner(System.in);

    ChatLanguageModel model = null;

    public static void main(String[] args){
        if(System.currentTimeMillis()<0) {//make this >0 to stop processing
            throw new NotImplementedException("Please complete the logic necessary to execute this use case...");
        }
        jedis = new JedisPooledGetter(args).getJedisPooled();
        eventLogger.setJedis(jedis).initTS();
        topKEntryLogger.setJedis(jedis);

        VSSSemanticCachedLLMExchangeComplete vssSemanticCachedLLMExchange = new VSSSemanticCachedLLMExchangeComplete();
        String userResponse = "";
        while(!userResponse.equalsIgnoreCase("end")){
            vssSemanticCachedLLMExchange.displayAndUpdateResponse();
            System.out.println("{{{{{{   USER OPTION   }}}}}}\n\t" +
                    "To end this program type: 'end' and hit enter, " +
                    "to get another LLM response, just hit enter...  ");
            userResponse = in.nextLine();
        }
        System.out.println("\n\n\tEnding program...");
    }

    ChatLanguageModel getLLMModel(){
        if(null==model) {
            model = OpenAiChatModel.builder()
                    .apiKey(APIKEYS.getDemoKey())//APIKEYS.getOPENAIKEY()
                    .maxTokens(30)
                    .timeout(ofSeconds(60))
                    .build();
        }
        return model;
    }

    void displayAndUpdateResponse(){
        System.out.println("My LLM model is - " + getLLMModel());
        System.out.println("Write one of these words: 'rhyme' or 'joke' or 'why' and hit enter:  ");
        String action = in.nextLine();
        System.out.println("Provide a single word topic and hit enter:  ");
        String topic = in.nextLine();
        String response = "";
        topKEntryLogger.addEntryToMyTopKKey("topic: "+topic+" action: "+action);
        long startTime = System.currentTimeMillis();
        long endTime = 0l;
        if(!satisfiedByCache(topic,action)) {
            response = getResponses(topic,action);
            endTime=System.currentTimeMillis();
            System.out.println("\n************** RESPONSE ********************\n");
            System.out.println(response);
            System.out.println("\nPlease rate the last response given above ^ on a scale of 1-10 with 10 being the best." +
                    " \n(Enter a digit from 1 to 10 then hit enter):  ");
            int rating = Integer.parseInt(in.nextLine().trim());
            //add response to cache in Redis:
            // //(uses Hash datatype to allow for additional fields and search indexing)
            String cachedLLMExchangeKeyName ="llm:exchange:"+topic+":"+action;
            jedis.hset(cachedLLMExchangeKeyName,"response",response);
            jedis.hincrBy(cachedLLMExchangeKeyName,"userRating",rating);
            //TODO: add embedding for the prompt that generated the response to the cached response object:
            //jedis.hset(cachedLLMExchangeKeyName.getBytes(), "embedding".getBytes(), createEmbedding(text));
        }else{
            endTime=System.currentTimeMillis();
        }
        System.out.println("\n<<< latency report >>>\n" +
                "Retrieving response took "+(endTime-startTime)+" milliseconds\n");
    }

    boolean satisfiedByCache(String topic,String action){
        boolean isSatisfiedByCache = false;
        String response = "Nothing cached - Need to call LLM...\n";
        String SEARCH_INDEX_NAME = "?";//TODO: <-- did you create an index? Does it use the prefix that matches?
        /*
          * TODO: sample FT.CREATE command (use outside of this program)
          * FT.CREATE idx_llm_exchanges ON hash PREFIX 1 "llm:exchange:" SCHEMA userRating NUMERIC SORTABLE embedding VECTOR FLAT 6 DIM 768 DISTANCE_METRIC COSINE TYPE FLOAT32
         */
        //TODO: -- perform a search query for semantic matches to the 'topic' and 'action'
        //It is useful to create a sentence that reflects a representation of the underlying request being made:
        String textForEmbedding = "Write me a response on the topic of the "+topic+" deliver it in the context of a "+action+" styled request";
        if(jedis.exists(SEARCH_INDEX_NAME)){
            response = executeVSSQuery(SEARCH_INDEX_NAME,textForEmbedding);
            isSatisfiedByCache=true;
            System.out.println("\n************** RESPONSE ********************\n");
        }
        System.out.println(response+"\n");
        return isSatisfiedByCache;
    }

    String getResponses(String topic,String action){
        String response = "";
        if (action.equalsIgnoreCase("rhyme")) {
            response = "" +
                    model.generate(
                            PROMPTS.YOU_ARE_A_BOT_WHO_RHYMES.apply(topic).text());
            eventLogger.addEventToMyTSKey(topic.length() / 4);
        } else if (action.equalsIgnoreCase("joke")) {
            response = "" +
                    model.generate(
                            PROMPTS.YOU_ARE_A_BOT_WHO_TELLS_DAD_JOKE_PUNCHLINES.apply(topic).text());
            eventLogger.addEventToMyTSKey(topic.length() / 4);
        } else {
            String question =
                    model.generate(
                            PROMPTS.YOU_ARE_A_TODDLER_BOT.apply(topic).text());
            response = "question :"+question+"\n\n" +
                    model.generate(
                            PROMPTS.YOU_ARE_A_KIND_BOT_WHO_ANSWERS.apply(question).text());
            eventLogger.addEventToMyTSKey((topic.length() / 4) + (question.length() / 4));
        }
        return response;
    }

    //TODO: COMPLETE ME!
    //TODO: implement query logic FT.SEARCH...
    //TODO: be certain to return the @response field from the best matching search result
    String executeVSSQuery(String SEARCH_INDEX_NAME,String textForEmbedding){
        String cachedResponseFoundBySearchingUsingVSS = "not found";
        //TODO: create embedding to use for comparison and
        // issue a search query to return the best matching response
        /*
                int K = 3;
        Query q = new Query("*=>[KNN $K @embedding $BLOB AS score]").
                setSortBy("score",false).
                returnFields("biography", "score"). //<-- response field!  That's the one!
                addParam("K", K).
                addParam("BLOB", createEmbedding(String textForEmbedding)).
                limit(0,1).
                dialect(2);

        // Execute the query
        List<Document> docs = jedis.ftSearch(SEARCH_INDEX_NAME, q).getDocuments();
        cachedResponseFoundBySearchingUsingVSS = String.valueOf(sr.getDocuments().get(0))
         */
        return cachedResponseFoundBySearchingUsingVSS;
    }

    byte[] createEmbedding(String textToEmbed){
        long[] la = getEmbeddingModel().encode(textToEmbed).getIds();
        return longArrayToByteArray(la);
    }

    HuggingFaceTokenizer getEmbeddingModel(){
        // Create the embedding model
        if(null==sentenceTokenizer) {
            Map<String, String> options = Map.of("maxLength", "768", "modelMaxLength", "768");
            sentenceTokenizer = HuggingFaceTokenizer.newInstance("sentence-transformers/all-mpnet-base-v2", options);
        }
        return sentenceTokenizer;
    }
}
