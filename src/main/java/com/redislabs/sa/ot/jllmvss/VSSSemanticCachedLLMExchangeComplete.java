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
//DONE: implemented query logic to ensure low user-scored results are filtered out @userRating:[4 +inf]
//TODO: remember to create a Search index similar to this: (run the command from redis-cli)
//TODO: FT.CREATE idx_llm_exchanges ON hash PREFIX 1 "llm:exchange:" LANGUAGE ENGLISH SCHEMA userRating NUMERIC SORTABLE embedding VECTOR FLAT 6 DIM 768 DISTANCE_METRIC L2 TYPE FLOAT32

/**
 * To make this go you would use a command like this:
 *  mvn compile exec:java -Dexec.cleanupDaemonThreads=false -Dexec.args="-h redis-12000.homelab.local -p 12000 -s p@ssword333 -vsssemanticcachecomplete"
 **/

public class VSSSemanticCachedLLMExchangeComplete {
    HuggingFaceTokenizer sentenceTokenizer = null;
    static TimeSeriesEventLogger eventLogger = new TimeSeriesEventLogger().
            setTSKeyNameForMyLog("VSSSemanticCache:GetAResponseEvent").
            setCustomLabel(TimeSeriesEventLogger.COUNTING_TOKENS_USED);
    static TimeSeriesEventLogger vssDurationLogger = new TimeSeriesEventLogger().
            setTSKeyNameForMyLog("VSSSemanticCache:VSSSearchTime").
            setCustomLabel(TimeSeriesEventLogger.DURATION_IN_MILLISECONDS);
    static TimeSeriesEventLogger llmDurationLogger = new TimeSeriesEventLogger().
            setTSKeyNameForMyLog("VSSSemanticCache:LLMExchangeTime").
            setCustomLabel(TimeSeriesEventLogger.DURATION_IN_MILLISECONDS);

    static TopKEntryLogger topKEntryLogger = new TopKEntryLogger();//use default topkKeyName
    static JedisPooled jedis = null;
    static Scanner in = new Scanner(System.in);
    static final String SEARCH_INDEX_NAME = "idx_llm_exchanges";
    /*
     * TODO: sample FT.CREATE command (execute this using redis-cli or similar, outside of this program)
        FT.CREATE idx_llm_exchanges ON hash PREFIX 1 "llm:exchange:" LANGUAGE ENGLISH SCHEMA userRating NUMERIC SORTABLE embedding VECTOR FLAT 6 DIM 768 DISTANCE_METRIC COSINE TYPE FLOAT32
     */

    ChatLanguageModel model = null;

    public static void main(String[] args){
        jedis = new JedisPooledGetter(args).getJedisPooled();
        eventLogger.setJedis(jedis).initTS();
        llmDurationLogger.setJedis(jedis).initTS();
        vssDurationLogger.setJedis(jedis).initTS();
        topKEntryLogger.setJedis(jedis);

        VSSSemanticCachedLLMExchangeComplete vssSemanticCachedLLMExchange = new VSSSemanticCachedLLMExchangeComplete();
        String userResponse = "";
        while(!userResponse.equalsIgnoreCase("end")){
            if(userResponse.equalsIgnoreCase("list")){
                System.out.println("Here are the top 5 user selected topics and requested actions: ");
                topKEntryLogger.getTopKlistWithCount(true);
                System.out.println("\n");
            }
            vssSemanticCachedLLMExchange.displayAndUpdateResponse();
            System.out.println("{{{{{{   USER OPTION   }}}}}}\n\t" +
                    "To end this program type: 'end' and hit enter, \n" +
                    "To see what others have asked for type: 'list' and hit enter, \n"+
                    "To ask for another LLM response, just hit enter...  ");
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
        System.out.println("Provide a single word, or very short topic \nExample: dogs tail wagging\n and hit enter:  ");
        String topic = in.nextLine();
        PromptAndResponse promptAndResponse = null;
        String promptSummary = "topic: "+topic+" action: "+action;
        topKEntryLogger.addEntryToMyTopKKey(promptSummary);

        long endTime = 0l;
        boolean skipCache = false;
        System.out.println("{{{{{{   USER OPTION   }}}}}}\n\t"+
                "If you want to SKIP the Cache" +
                "\n\tTYPE: Y and hit enter --> else, to try a VSS search hit enter...");
        if(in.nextLine().trim().equalsIgnoreCase("Y")){
            skipCache = true;
        }
        long startTime=System.currentTimeMillis();

        if(skipCache||(!satisfiedByCache(promptSummary))){
            startTime = System.currentTimeMillis();
            // we don't like the cache so use LLM:
            promptAndResponse = getLLMPromptAndResponse(topic,action);
            endTime=System.currentTimeMillis();
            llmDurationLogger.addEventToMyTSKey(endTime);
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
            //FIXME: add embedding for the prompt that generated the response to the cached response object:
            //This has been updated to utilize the prompt summary instead of the actual prompt in the hopes that it works
            pipeline.hset(cachedLLMExchangeKeyName.getBytes(), "embedding".getBytes(), createEmbedding(promptSummary));
            pipeline.sync();
        }else{ // we used the VSS cache to get our response
            endTime=System.currentTimeMillis();
        }
        System.out.println("\n<<< latency report >>>\n" +
                "Retrieving response took "+(endTime-startTime)+" milliseconds\n");
    }

    //: -- perform a search query for semantic matches
    // to the prompt previously used on the 'topic' and 'action'
    boolean satisfiedByCache(String promptSummary){
        boolean isSatisfiedByCache = false;
        String response = "Nothing cached - Need to call LLM...\n";
        //It may be useful to create a sentence that reflects a representation of the underlying request being made:
        if(!jedis.ftInfo(SEARCH_INDEX_NAME).isEmpty()){
            long startTime = System.currentTimeMillis();
            response = executeVSSQuery(SEARCH_INDEX_NAME,promptSummary);
            vssDurationLogger.addEventToMyTSKey((System.currentTimeMillis()-startTime));
            isSatisfiedByCache=true;
            System.out.println("\n************** RESPONSE ********************\n");
        }
        System.out.println(response+"\n");
        if(response.trim().equalsIgnoreCase("not found")){
            isSatisfiedByCache=false;
        }
        return isSatisfiedByCache;
    }

    //used if we are searching for prompt embeddings:
    String getLLMPrompt(String topic, String action){
        String prompt = "";
        if (action.equalsIgnoreCase("rhyme")) {
            prompt =// "Create a rhyming phrase featuring "+topic;
                    PROMPTS.YOU_ARE_A_BOT_WHO_RHYMES.apply(topic).text();
        } else if (action.equalsIgnoreCase("joke")) {
            prompt = //"Write a short dad joke featuring "+topic;
                    PROMPTS.YOU_ARE_A_BOT_WHO_TELLS_DAD_JOKE_PUNCHLINES.apply(topic).text();
        } else {
            prompt = "Answer a short simple question using why, featuring "+topic;
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

    //: implement query logic FT.SEARCH...
    //: be certain to return the @response field from the best matching search result
    // We use cached *prompts* as retrieval search filters for responses
    // Good thing: We reject bad responses both on the basis of user feedback and VSS score
    // TODO: figure out a better query to filter the 'why' style interactions...
    //  trying to avoid calling the LLM again, cuz what is the point of such a cache?
    //  answer: at least you can be sure the result has a high yserScore if cached
    String executeVSSQuery(String SEARCH_INDEX_NAME,String textForEmbedding){
        String cachedResponseFoundBySearchingUsingVSS = "not found";
        //TODO: create embedding to use for comparison and
        // issue a search query to return the best matching response
        byte[] embedding = createEmbedding(textForEmbedding);
        int K = 3; //only retrieve nearest 3 results to embedding search result
        DebugRedisSearchJedisQuery queryDebugger = new DebugRedisSearchJedisQuery();
        /*
        Query q = new Query(
                "*=>[KNN $K @embedding $BLOB AS vss_score]").
                setSortBy("vss_score",true). // with cosine a low score is the best matching result
                returnFields("response", "vss_score", "userRating","lastRecordedPrompt"). //what will come back to us from Redis?
                addParam("K", K).
                addParam("BLOB", embedding).
                limit(0,1).
                dialect(2);
         */
        Query q=queryDebugger.startQuery(
                "@userRating:[4 +inf]=>[KNN $K @embedding $BLOB AS vss_score]");
        q=queryDebugger.setSortBy("vss_score",true); // with cosine a low score is the best matching result
        q=queryDebugger.returnFields("response", "vss_score", "userRating","lastRecordedPrompt"); //what will come back to us from Redis?
        q=queryDebugger.addParam("K", K);
        q=queryDebugger.addParam("BLOB", embedding);
        q=queryDebugger.limit(0,1);
        q=queryDebugger.dialect(2);
        System.out.println("WHAT VSS QUERY ARE WE TRYING: \nUsing this text as the BLOB...\t"+textForEmbedding+"\n"+queryDebugger.toString()+"\n\n");
        // Execute the query
        List<Document> docs = jedis.ftSearch(SEARCH_INDEX_NAME, q).getDocuments();
        if(docs.size()>0) {
            System.out.println("WHAT DID VSS SEARCH RETURN: \n" + docs.get(0) + "\n\n");
            cachedResponseFoundBySearchingUsingVSS = String.valueOf(docs.get(0).get("response"));
            float score =Float.parseFloat(String.valueOf(docs.get(0).get("vss_score")));
            System.out.println("SCORE returned from VSS search was: "+score);
            //if(score < 80.25f){
            if(score > 0.00025){
                cachedResponseFoundBySearchingUsingVSS = "not found";
            }
            int userRating =5;
            try{
                userRating = Integer.parseInt(String.valueOf(docs.get(0).get("userRating")));
            }catch(Throwable t){}
            if(userRating<4){
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
