package com.redislabs.sa.ot.jllmvss;

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.apache.poi.ss.formula.eval.NotImplementedException;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.Pipeline;
import redis.clients.jedis.params.ScanParams;
import redis.clients.jedis.resps.ScanResult;
import redis.clients.jedis.search.Document;
import redis.clients.jedis.search.Query;

import java.io.File;
import java.io.FileReader;
import java.util.*;

import static com.redislabs.sa.ot.jllmvss.ByteArrayHelper.longArrayToByteArray;
import static java.time.Duration.ofSeconds;

/**
 * This is an example of using RAG to augment the LLM responses so it focuses on a specific domain of knowledge
 * Of particular interest are the many techniques available to load the relevant data
 * -from local file system - not scalable
 * -from Redis - fast and scalable and facilitated by search to hone in on more relevant subsets of the docs
 * You would start this program in this mode by executing:
 * mvn compile exec:java -Dexec.cleanupDaemonThreads=false -Dexec.args="-h redis-12000.homelab.local -p 12000 -s password -biographyexpert"
 *
 * Note that in many cases- separate calls to jedis would be better executed in a pipeline or
 * wrapped in a single transaction-bounded call
 * NB: it is expected that you create 2 Search indexes like this:
 * FT.CREATE idx_llm_exchanges ON hash PREFIX 1 "llm:exchange:" SCHEMA userRating NUMERIC SORTABLE chunkStrategy TAG embedding VECTOR FLAT 6 DIM 768 DISTANCE_METRIC COSINE TYPE FLOAT32
 * and this:
 * FT.CREATE idx_biography_expert ON hash PREFIX 1 "llm:biography:data:" LANGUAGE ENGLISH STOPWORDS 0 SCHEMA chunkStrategy TAG biographyText TEXT
 */
public class BiographyExpert {
    HuggingFaceTokenizer sentenceTokenizer = null;
    static TimeSeriesEventLogger eventLogger = new TimeSeriesEventLogger().
            setTSKeyNameForMyLog("BiographyExpert:GetAResponseEvent").
            setCustomLabel("countingTokensUsed");
    static TimeSeriesEventLogger durationLogger = new TimeSeriesEventLogger().
            setTSKeyNameForMyLog("BiographyExpert:EmbeddingEventDuration").
            setCustomLabel("durationInMilliseconds");
    static TopKEntryLogger topKEntryLogger = new TopKEntryLogger().
            setTopKKeyNameForMyLog("topk:biographyExpertKeywords");//use kneeExpert keyName
    static JedisPooled jedis = null;
    static Scanner in = new Scanner(System.in);
    static final String BIOGRAPHY_EXPERT_SEARCH_INDEX_NAME = "idx_biography_expert";
    static final String BIOGRAPHY_EXPERT_SEARCH_KEY_PREFIX = "llm:biography:data:";
    static final String BIOGRAPHY_SEARCH_RESULT_FIELD = "biographyText";
    static final String LLM_EXCHANGES_SEARCH_INDEX_NAME = "idx_llm_exchanges";
    static final String LLM_EXCHANGES_SEARCH_KEY_PREFIX = "llm:exchange:";
    static final String LLM_EXCHANGES_SEARCH_RESULT_FIELD = "response";

    ChatLanguageModel model = null;

    public static void main(String [] args){
        jedis = new JedisPooledGetter(args).getJedisPooled();
        eventLogger.setJedis(jedis).initTS();
        durationLogger.setJedis(jedis).initTS();
        topKEntryLogger.setJedis(jedis);
        BiographyExpert biographyExpert = new BiographyExpert();
        biographyExpert.loadBioTextIntoSeparateRedisHashes(false);//forceLoad loads data into Hashes regardless of previous existence
        if(System.currentTimeMillis()<0) {
            throw new NotImplementedException("Please complete the logic necessary to execute this use case...");
        }
        biographyExpert.getLLMModel();
        String userResponse = "";
        while(!userResponse.equalsIgnoreCase("end")){
            biographyExpert.interactWithUser();
            System.out.println("{{{{{{   USER OPTION   }}}}}}\n\t" +
                    "To end this program type: 'end' and hit enter, " +
                    "to get another LLM response, just hit enter...  ");
            userResponse = in.nextLine();
        }
        System.out.println("\n\n\tEnding program...");
    }

    void interactWithUser(){
        String[] list = new String[]{"Laura","Jake","Karisa","Violin","Cello","Viola","Juilliard","Yale","Beethoven","Schummann","New York","Korea","Africa","India","Emerson","Shepherd","Michigan","Prague","Taos"};
        ArrayList<String> biographyKeywords = new ArrayList(Arrays.asList(list));
        System.out.println("Please select (write) the corresponding number (digit) for one of the words matching your interest: \n" +
                "and hit enter:  \n Here is the list with the numbers you should choose from:");
        int counter=0;
        for(String s:biographyKeywords) {
            counter=counter+1;
            System.out.println("\t"+counter+"\t"+s);
        }
        int keywordIdx = Integer.parseInt(in.nextLine().trim());
        String chosenKeyword = biographyKeywords.get(keywordIdx-1);
        String response = "";
        topKEntryLogger.addEntryToMyTopKKey("biographyKeyword: "+chosenKeyword);
        System.out.println("Please select (write) the corresponding number (digit) for another one of the words and hit enter:  \n");
        keywordIdx = Integer.parseInt(in.nextLine().trim());
        String secondBiographyKeyword = biographyKeywords.get(keywordIdx-1);
        topKEntryLogger.addEntryToMyTopKKey("biographyKeyword: "+secondBiographyKeyword);
        long startTime = System.currentTimeMillis();
        long endTime = 0l;
        String allKeywords = chosenKeyword+" or "+secondBiographyKeyword;
        String searchFullText = chosenKeyword+" | "+secondBiographyKeyword;
        String prompt = PROMPTS.YOU_ARE_CURIOUS_ABOUT_BIOGRAPHIES.apply(allKeywords).text();
        String promptToEmbed = prompt.split("with:")[1];

        //first try to match a cached prompt using embedding and VSS search:
        if(!satisfiedBySemanticCache(promptToEmbed)) {
            //construct augmented prompt using results of FT.search using allKeywords (not embedding)
            response = getResponses(searchFullText);
            System.out.println("\n************** RESPONSE ********************\n");
            System.out.println(response);
        }
        endTime = System.currentTimeMillis();
        System.out.println("\nPlease rate the last response given above ^  with 1 being good and -1 being not good." +
                " \n(Enter 1 or -1 then hit enter):  ");
        int rating = Integer.parseInt(in.nextLine().trim());
        //add response to cache in Redis:
        //(uses Hash datatype to allow for additional fields and search indexing)
        if((response.length()>1)&&rating>0) { //you may prefer to cache the non-responses too to allow human fix to data
            String cachedLLMExchangeKeyName = LLM_EXCHANGES_SEARCH_KEY_PREFIX + chosenKeyword + ":" + secondBiographyKeyword;
            Pipeline jedisPipeline = jedis.pipelined();
            jedisPipeline.hset(cachedLLMExchangeKeyName, LLM_EXCHANGES_SEARCH_RESULT_FIELD, response);//NB: opportunity to use separate String key for response
            jedisPipeline.hincrBy(cachedLLMExchangeKeyName, "userRating", rating);
            jedisPipeline.hset(cachedLLMExchangeKeyName,"promptEmbeddingOriginalText",promptToEmbed);
            //add embedding for the PROMPT that generated the response to the cached response object:
            jedisPipeline.hset((cachedLLMExchangeKeyName).getBytes(), "embedding".getBytes(), createEmbedding(promptToEmbed));
            jedisPipeline.sync();
        }
        System.out.println("\n<<< latency report >>>\n" +
                "Retrieving response took "+(endTime-startTime)+" milliseconds\n");
        testNonAugmentedResponse(promptToEmbed);
    }

    void testNonAugmentedResponse(String promptToEmbed) {
        System.out.println("\n As a test of the value of the augmented generation, " +
                "we will re-execute the call to the LLM using only : "+promptToEmbed+" \n\tpress enter to proceeed...");
        in.nextLine();
        String response = model.generate(
                PROMPTS.YOU_ARE_AN_EXPERT_IN_BIOGRAPHIES.apply(promptToEmbed).text());
        System.out.println("\n\n$$$$$  NON-AUGMENTED-RESPONSE  $$$$$\n\n"+response+"\n\n");
    }

    //Here we use VSS search for the stored LLM prompt and response driven by the user selection:
    //we are not searching through the Biographies themselves
    //NB: only userRating of =>2 will be returned even if there is a cached response
    boolean satisfiedBySemanticCache(String searchText){
        boolean isSatisfiedByCache = false;
        String response = "Nothing cached - Need to call LLM...\n";
        // Create the embedding model
        Map<String, String> options = Map.of("maxLength", "768",  "modelMaxLength", "768");
        HuggingFaceTokenizer sentenceTokenizer = HuggingFaceTokenizer.newInstance("sentence-transformers/all-mpnet-base-v2", options);
        System.out.println("satisfiedBySemanticCache(): About to query Redis using "+searchText);
        int K = 5;
        //String notUsedFilters = "@userRating:[2,+inf] @chunkStrategy:{\"+ChunkStrategies.FULL_TEXT_SUMMARY+\"}";
/*
        Query q = new Query ("@embedding:[VECTOR_RANGE .02 " +
                "$vec_param]=>{{$yield_distance_as: range_dist}})" +
                "=>" +
                "[KNN 10 @embedding $knn_vec]=>{{$yield_distance_as: knn_dist}}").
                setSortBy("knn_dist",true).
                returnFields(LLM_EXCHANGES_SEARCH_RESULT_FIELD, "score").
                addParam("vec_param", longArrayToByteArray(sentenceTokenizer.encode(searchText).getIds())).
                addParam("knn_vec", longArrayToByteArray(sentenceTokenizer.encode(searchText).getIds())).
                limit(0,1).
                dialect(2);
*/

        Query q = new Query("*=>[KNN $K @embedding $BLOB AS score]").
                setSortBy("score",false).
                returnFields(LLM_EXCHANGES_SEARCH_RESULT_FIELD, "score").
                addParam("K", K).
                addParam("BLOB", longArrayToByteArray(sentenceTokenizer.encode(searchText).getIds())).
                limit(0,1).
                dialect(2);


        // Execute the query
        List<Document> docs = jedis.ftSearch(LLM_EXCHANGES_SEARCH_INDEX_NAME, q).getDocuments();
        String bestMatch = "";
        for(Document d:docs){
            bestMatch = d.getString(LLM_EXCHANGES_SEARCH_RESULT_FIELD);
            if(bestMatch.length()>1) {
                isSatisfiedByCache = true;
                response=bestMatch;
            }
        }
        System.out.println("\n************** RESPONSE ********************\n");
        System.out.println(response+"\n");
        return isSatisfiedByCache;
    }

    String getResponses(String searchTerms){
        System.out.println("Building prompt by searching for : \n"+searchTerms);
        long startTime = System.currentTimeMillis();
        String response = "";
        String retrievedText = getSearchResponseForChosenSearchTerms(searchTerms,ChunkStrategies.FULL_TEXT_SUMMARY);
        System.out.println("\nSource text retrieved by keyword is: "+retrievedText);
        String question =
                model.generate(
                        PROMPTS.YOU_ARE_CURIOUS_ABOUT_BIOGRAPHIES.apply(searchTerms+" and "+retrievedText).text());
        System.out.println("\nPrompting using this text: "+question);
        eventLogger.addEventToMyTSKey((searchTerms.length() / 4) + (question.length() / 4));
        response =
                model.generate(
                        PROMPTS.YOU_ARE_AN_EXPERT_IN_BIOGRAPHIES.apply(" Considering "+retrievedText+" I want to know... "+question).text());
        String unfinishedStatement = ".";
        eventLogger.addEventToMyTSKey((retrievedText.length()/4)+(searchTerms.length() / 4) + (question.length() / 4));
        if(response.length()<150){
            System.out.println("triggered enhancement of response...");
            unfinishedStatement = response;
            response+=" "+model.generate(PROMPTS.YOU_COMPLETE_WHAT_YOU_START.apply(unfinishedStatement).text());
        }
        eventLogger.addEventToMyTSKey((unfinishedStatement.length()/4)+(searchTerms.length() / 4) + (question.length() / 4));
        durationLogger.addEventToMyTSKey(System.currentTimeMillis()-startTime);
        return response;
    }

    //Use standard Search to retrieve the text which will be used to generate the augmented response
    String getSearchResponseForChosenSearchTerms(String searchTerms, String chunkStrategy){
        String result = "  Correction: No Match for those Terms - try again";
        Query q = new Query("@"+BIOGRAPHY_SEARCH_RESULT_FIELD+":("+searchTerms+")"+" @chunkStrategy:{"+chunkStrategy+"}").
                limit(0,1).
                dialect(2);
        // Execute the query:
        List<Document> docs = jedis.ftSearch(BIOGRAPHY_EXPERT_SEARCH_INDEX_NAME, q).getDocuments();
        if(docs.size()>0){result =String.valueOf(docs.get(0).get(BIOGRAPHY_SEARCH_RESULT_FIELD))+" "+String.valueOf(docs.get(0).get("source"));}
        return result;
    }

    //one way to get at the text will be to store it in an indexed field in redis and
    //then search for matching tokens and return a few dozen tokens as surrounding context
    //This may be useful for use by an LLM in creating an enriched prompt which it then submits to itsself
    //It might be enough to provide the context needed to answer a question with more detail than usual
    void loadBioTextIntoSeparateRedisHashes(boolean forceLoad) {
        String nextBiographyDataKey = BIOGRAPHY_EXPERT_SEARCH_KEY_PREFIX+"_"+System.currentTimeMillis();
        Scanner fileScanner = null;
        String biographyText = "";
        try {
            File file = new File("src/main/resources/biographies.txt");
            long timeLastModified = file.lastModified();
            ScanParams scanParams = new ScanParams().match(BIOGRAPHY_EXPERT_SEARCH_KEY_PREFIX+"*").count(100000);
            ScanResult<String> hashesCreatedFromFileCount = jedis.scan("0",scanParams);
            if((hashesCreatedFromFileCount.getResult().size()<1)||(forceLoad)) { //load data unless exists or forced to
                fileScanner = new Scanner(new FileReader("src/main/resources/biographies.txt"));
                while (fileScanner.hasNextLine()) {
                    biographyText = fileScanner.nextLine();
                    System.out.println("SOURCE LINK for next biographical key:\n" + biographyText);
                    nextBiographyDataKey = BIOGRAPHY_EXPERT_SEARCH_KEY_PREFIX + "_" + System.currentTimeMillis();
                    Pipeline jedisPipeline = jedis.pipelined();
                    jedisPipeline.hset(nextBiographyDataKey, "source", biographyText);//by convention this will be the URL to the source
                    biographyText = fileScanner.nextLine();//Skip to the next block of text which is the actual biography for the source...
                    System.out.println("TEXT for next biographical key:\n" + biographyText);
                    jedisPipeline.hset(nextBiographyDataKey, BIOGRAPHY_SEARCH_RESULT_FIELD, biographyText);
                    jedisPipeline.hset(nextBiographyDataKey, "chunkStrategy", ChunkStrategies.FULL_TEXT_SUMMARY);//later we can filter on strategy
                    jedisPipeline.sync();
                }
            }
        } catch (Throwable t) {
            t.printStackTrace();
            System.exit(0);
        }//This is a major issue, so we exit the program
    }

    ChatLanguageModel getLLMModel(){
        if(null==model) {
            model = OpenAiChatModel.builder()
                    .apiKey(APIKEYS.getDemoKey())//APIKEYS.getOPENAIKEY()
                    //.apiKey(APIKEYS.getOPENAIKEY())
                    .maxTokens(80)
                    .timeout(ofSeconds(60))
                    .build();
        }
        return model;
    }

    HuggingFaceTokenizer getEmbeddingModel(){
        // Create the embedding model
        if(null==sentenceTokenizer) {
            Map<String, String> options = Map.of("maxLength", "768", "modelMaxLength", "768");
            sentenceTokenizer = HuggingFaceTokenizer.newInstance("sentence-transformers/all-mpnet-base-v2", options);
        }
        return sentenceTokenizer;
    }

    byte[] createEmbedding(String textToEmbed){
        long[] la = getEmbeddingModel().encode(textToEmbed).getIds();
        return longArrayToByteArray(la);
    }
}