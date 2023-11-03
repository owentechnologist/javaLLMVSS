package com.redislabs.sa.ot.jllmvss;

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.apache.poi.ss.formula.eval.NotImplementedException;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.Pipeline;
import redis.clients.jedis.search.Document;
import redis.clients.jedis.search.Query;

import java.util.List;
import java.util.Map;
import java.util.Scanner;

import static com.redislabs.sa.ot.jllmvss.ByteArrayHelper.longArrayToByteArray;
import static java.time.Duration.ofSeconds;

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
    static final String SEARCH_INDEX_NAME = "idx_llm_exchanges";
    /*
     * TODO: sample FT.CREATE command (use outside of this program)
     * FT.CREATE idx_llm_exchanges ON hash PREFIX 1 "llm:exchange:" SCHEMA userRating NUMERIC SORTABLE embedding VECTOR FLAT 6 DIM 768 DISTANCE_METRIC COSINE TYPE FLOAT32
     */

    ChatLanguageModel model = null;

    public static void main(String[] args){
        if(System.currentTimeMillis()<0) {//make this >0 to stop processing
            throw new NotImplementedException("Please complete the logic necessary to execute this use case...");
        }
        // by default we will use cached prompts as retrieval search filters for responses
        // because we are allowing the topK nearest neighbors and not filtering by score to eliminate
        // completely unrelated content - we may want to seed the content before allowing caching
        // ultimately, a better search query that filters on the nearness and
        // disallows too much distance should be used
        // TODO: figure out a better query to filter on KNN distance and reject too much distance
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
        PromptAndResponse promptAndResponse = null;
        topKEntryLogger.addEntryToMyTopKKey("topic: "+topic+" action: "+action);
        long startTime = System.currentTimeMillis();
        long endTime = 0l;
        boolean skipCache = false;
        System.out.println("If you want to SKIP the Cache and build a new prompt and Response type: Y and hit enter - else just hit enter...\n");
        if(in.nextLine().trim().equalsIgnoreCase("Y")){
            skipCache = true;
        }
        if(skipCache||(!satisfiedByCache(topic,action))){
            // we don't like the cache so use LLM:
            promptAndResponse = getLLMPromptAndResponse(topic,action);
            endTime=System.currentTimeMillis();
            System.out.println("\n************** RESPONSE ********************\n");
            System.out.println(promptAndResponse.response);
            System.out.println("\nPlease rate the last response given above ^ on a scale of 1-10 with 10 being the best." +
                    " \n(Enter a digit from 1 to 10 then hit enter):  ");
            int rating = Integer.parseInt(in.nextLine().trim());
            //add response to cache in Redis:
            // //(uses Hash datatype to allow for additional fields and search indexing)
            String cachedLLMExchangeKeyName ="llm:exchange:"+topic+":"+action;
            Pipeline pipeline = jedis.pipelined();
            pipeline.hset(cachedLLMExchangeKeyName,"lastRecordedPrompt", promptAndResponse.prompt);
            pipeline.hset(cachedLLMExchangeKeyName,"response", promptAndResponse.response);
            pipeline.hincrBy(cachedLLMExchangeKeyName,"userRating",rating);
            //TODO: add embedding for the prompt that generated the response to the cached response object:
            jedis.hset(cachedLLMExchangeKeyName.getBytes(), "embedding".getBytes(), createEmbedding(promptAndResponse.response));
            pipeline.sync();
        }else{
            endTime=System.currentTimeMillis();
        }
        System.out.println("\n<<< latency report >>>\n" +
                "Retrieving response took "+(endTime-startTime)+" milliseconds\n");
    }

    boolean satisfiedByCache(String topic,String action){
        boolean isSatisfiedByCache = false;
        String response = "Nothing cached - Need to call LLM...\n";
        //TODO: -- perform a search query for semantic matches to the prompt previously used on the 'topic' and 'action'
        String promptForSearchingAgainst = getLLMPrompt(topic,action);
        //It is useful to create a sentence that reflects a representation of the underlying request being made:
        if(!jedis.ftInfo(SEARCH_INDEX_NAME).isEmpty()){
            response = executeVSSQuery(SEARCH_INDEX_NAME,promptForSearchingAgainst);
            isSatisfiedByCache=true;
            System.out.println("\n************** RESPONSE ********************\n");
        }
        System.out.println(response+"\n");
        if(response.trim().equalsIgnoreCase("not found")){
            isSatisfiedByCache=false;
        }
        return isSatisfiedByCache;
    }

    String getLLMPrompt(String topic, String action){
        String prompt = "";
        if (action.equalsIgnoreCase("rhyme")) {
            prompt = "A Rhyming phrase featuring "+topic;
        } else if (action.equalsIgnoreCase("joke")) {
            prompt = "A joke featuring "+topic;
        } else {
            prompt = "A question asking why featuring "+topic;
        }
        return prompt;
    }


    //
    PromptAndResponse getLLMPromptAndResponse(String topic, String action){
        String response = "";
        String prompt = "";
        if (action.equalsIgnoreCase("rhyme")) {
            prompt = PROMPTS.YOU_ARE_A_BOT_WHO_RHYMES.apply(topic).text();
            response = "" +
                    model.generate(prompt);
            eventLogger.addEventToMyTSKey(topic.length() / 4);
        } else if (action.equalsIgnoreCase("joke")) {
            prompt = PROMPTS.YOU_ARE_A_BOT_WHO_TELLS_DAD_JOKE_PUNCHLINES.apply(topic).text();
            response = "" +
                    model.generate(prompt);
            eventLogger.addEventToMyTSKey(topic.length() / 4);
        } else {
            String question =
                    model.generate(
                            PROMPTS.YOU_ARE_A_TODDLER_BOT.apply(topic).text());
            prompt = PROMPTS.YOU_ARE_A_KIND_BOT_WHO_ANSWERS.apply(question).text();
                    response = "question :"+question+"\n\n" +
                    model.generate(prompt);
            eventLogger.addEventToMyTSKey((topic.length() / 4) + (question.length() / 4));
        }
        return new PromptAndResponse().buildPromptAndResponse(prompt,response);
    }

    //TODO: COMPLETE ME!
    //TODO: implement query logic FT.SEARCH...
    //TODO: be certain to return the @response field from the best matching search result
    String executeVSSQuery(String SEARCH_INDEX_NAME,String textForEmbedding){
        String cachedResponseFoundBySearchingUsingVSS = "not found";
        //TODO: create embedding to use for comparison and
        // issue a search query to return the best matching response
        byte[] embedding = createEmbedding(textForEmbedding);
        //FT.SEARCH idx "*=>[KNN 10 @vec $BLOB]=>{$EF_RUNTIME:
        // $EF; $YIELD_DISTANCE_AS: my_scores}" PARAMS 4 EF 150
        // BLOB "\x12\xa9\xf5\x6c"
        // SORTBY my_scores DIALECT 2
        int K = 3;
        DebugRedisSearchJedisQuery queryDebugger = new DebugRedisSearchJedisQuery();
        Query q = queryDebugger.startQuery(
                "*=>[KNN $K @embedding $BLOB AS score]").
                setSortBy("score",false). // a low score is the best matching result
                returnFields("response", "score"). //<-- response field!  That's the one!
                addParam("K", K).
                addParam("BLOB", embedding).
                limit(0,1).
                dialect(2);
        /*  TODO: This next query is broken .. need to investigate ...
        DebugRedisSearchJedisQuery q = (DebugRedisSearchJedisQuery) new DebugRedisSearchJedisQuery(
        //Query q = new Query (
                "(@embedding:[VECTOR_RANGE .02 " +
                "vec_param]=>{{yield_distance_as: range_dist}}))" +
                "=>" +
                "[KNN 10 @embedding knn_vec]=>{{yield_distance_as: knn_dist}}").
                setSortBy("knn_dist",true).//we want the smallest distance possible
                returnFields("response", "score").
                addParam("vec_param", embedding).
                addParam("knn_vec", embedding).
                limit(0,1).
                dialect(3);
        */
        System.out.println("WHAT VSS QUERY ARE WE TRYING: \n"+queryDebugger.toString()+"\n\n");
        // Execute the query
        List<Document> docs = jedis.ftSearch(SEARCH_INDEX_NAME, q).getDocuments();
        if(docs.size()>0) {
            System.out.println("WHAT DID VSS SEARCH RETURN: \n" + docs.get(0) + "\n\n");
            cachedResponseFoundBySearchingUsingVSS = String.valueOf(docs.get(0).get("response"));
            float score =Float.parseFloat(String.valueOf(docs.get(0).get("score")));
            System.out.println("SCORE returned from VSS search was: "+score);
            if(score > .75f){
                cachedResponseFoundBySearchingUsingVSS = "not found";
            }
        }
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

class PromptAndResponse{
    public String prompt = "";
    public String response = "";

    public PromptAndResponse buildPromptAndResponse(String prompt,String response){
        this.prompt=prompt;
        this.response=response;
        return this;
    }
}
