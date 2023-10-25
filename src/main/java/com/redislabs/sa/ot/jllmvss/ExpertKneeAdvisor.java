package com.redislabs.sa.ot.jllmvss;

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.search.Document;
import redis.clients.jedis.search.Query;

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
 * mvn compile exec:java -Dexec.cleanupDaemonThreads=false -Dexec.args="-h redis-12000.homelab.local -p 12000 -s password -expertkneeadvisor"
 */
public class ExpertKneeAdvisor {
    HuggingFaceTokenizer sentenceTokenizer = null;
    static TimeSeriesEventLogger eventLogger = new TimeSeriesEventLogger().
            setTSKeyNameForMyLog("ExpertKneeAdvisor:GetAResponseEvent").
            setCustomLabel("countingTokensUsed");
    static TimeSeriesEventLogger durationLogger = new TimeSeriesEventLogger().
            setTSKeyNameForMyLog("ExpertKneeAdvisor:EmbeddingEventDuration").
            setCustomLabel("durationInMilliseconds");
    static TopKEntryLogger topKEntryLogger = new TopKEntryLogger().
            setTopKKeyNameForMyLog("topk:kneeIssueKeywords");//use kneeExpert keyName
    static JedisPooled jedis = null;
    static Scanner in = new Scanner(System.in);
    static final String KNEE_EXPERT_SEARCH_INDEX_NAME = "idx_knee_expert";
    static final String KNEE_EXPERT_SEARCH_KEY_PREFIX = "llm:knee:data:";

    ChatLanguageModel model = null;

    public static void main(String [] args){
        if(System.currentTimeMillis()>0) {
            //throw new NotImplementedException("Please complete the logic necessary to execute this use case...");
        }
        jedis = new JedisPooledGetter(args).getJedisPooled();
        eventLogger.setJedis(jedis).initTS();
        durationLogger.setJedis(jedis).initTS();
        topKEntryLogger.setJedis(jedis);
        ExpertKneeAdvisor expertKneeAdvisor = new ExpertKneeAdvisor();
        expertKneeAdvisor.loadAllKneeAdviceTextIntoSingleRedisHash();
        expertKneeAdvisor.getLLMModel();
        String userResponse = "";
        while(!userResponse.equalsIgnoreCase("end")){
            expertKneeAdvisor.interactWithUser();
            System.out.println("{{{{{{   USER OPTION   }}}}}}\n\t" +
                    "To end this program type: 'end' and hit enter, " +
                    "to get another LLM response, just hit enter...  ");
            userResponse = in.nextLine();
        }
        System.out.println("\n\n\tEnding program...");
    }


    void interactWithUser(){
        String[] list = new String[]{"sitting","running","MCL","Iliotibial","swelling","kneecap","Arthritis","bursitis","Tendonitis","clicking","clunking","sedentary"};
        ArrayList<String> kneeIssueKeywords = new ArrayList(Arrays.asList(list));
        System.out.println("Please select (write) the corresponding number (digit) for one of the words matching your knee issue: \n" +
                "and hit enter:  \n Here is the list with the numbers you should choose from:");
        int counter=0;
        for(String s:kneeIssueKeywords) {
            counter=counter+1;
            System.out.println("\t"+counter+"\t"+s);
        }
        int keywordIdx = Integer.parseInt(in.nextLine().trim());
        String chosenKeyword = kneeIssueKeywords.get(keywordIdx-1);
        String response = "";
        topKEntryLogger.addEntryToMyTopKKey("kneeIssueKeyword: "+chosenKeyword);
        long startTime = System.currentTimeMillis();
        long endTime = 0l;
        response = getResponses(chosenKeyword);
        endTime=System.currentTimeMillis();
        System.out.println("\n************** RESPONSE ********************\n");
        System.out.println(response);
        System.out.println("\nPlease rate the last response given above ^ on a scale of 1-10 with 10 being the best." +
                " \n(Enter a digit from 1 to 10 then hit enter):  ");
        int rating = Integer.parseInt(in.nextLine().trim());
        //add response to cache in Redis:
        // //(uses Hash datatype to allow for additional fields and search indexing)
        //String cachedLLMExchangeKeyName =KNEE_EXPERT_SEARCH_KEY_PREFIX+chosenKeyword;
        //jedis.hset(cachedLLMExchangeKeyName,"response",response);
        //jedis.hincrBy(cachedLLMExchangeKeyName,"userRating",rating);
        //TODO: add embedding for the prompt that generated the response to the cached response object:
        //jedis.hset(KNEE_EXPERT_SEARCH_KEY_PREFIX+chosenKeyword.getBytes(), "embedding".getBytes(), createEmbedding(question));
        System.out.println("\n<<< latency report >>>\n" +
                "Retrieving response took "+(endTime-startTime)+" milliseconds\n");
        testNonAugmentedResponse();
    }

    void testNonAugmentedResponse() {
        System.out.println("\n As a test of this pattern, please ask the question posed " +
                "regarding your knee condition again, then, hit enter.\n" +
                "Feel free to copy paste the previous text prompt printed to ths screen" +
                " after the words: \'Prompting using this text: \' ");
        String newPrompt = in.nextLine().trim();
        String response = model.generate(
                        PROMPTS.YOU_ARE_AN_EXPERT_IN_KNEE_ISSUES.apply(newPrompt).text());
        System.out.println("\n\n$$$$$  NON-AUGMENTED-RESPONSE  $$$$$\n\n"+response+"\n\n");
    }

    String getResponses(String chosenKeyword){
        long startTime = System.currentTimeMillis();
        String response = "";
        String retrievedText = getSearchResponseForChosenKeyword(chosenKeyword,"none");
        System.out.println("\nSource text retrieved by keyword is: "+retrievedText);
        String question =
                model.generate(
                        PROMPTS.YOU_PROVIDE_CONTEXT.apply(chosenKeyword+" and "+retrievedText).text());
        System.out.println("\nPrompting using this text: "+question);
        //String context = "BEGIN: reply with thoughts on "+chosenKeyword+" You may draw knowledge from what follows "+retrievedText;
        response =
                model.generate(
                        PROMPTS.YOU_ARE_AN_EXPERT_IN_KNEE_ISSUES.apply(" Considering "+retrievedText+" I want to know... "+question).text());
        String unfinishedStatement = "";
        if(response.length()<150){
            System.out.println("triggered enhancement of response...");
            unfinishedStatement = response;
            response+=" "+model.generate(PROMPTS.YOU_COMPLETE_WHAT_YOU_START.apply(unfinishedStatement).text());
        }
        eventLogger.addEventToMyTSKey((unfinishedStatement.length()/4)+(chosenKeyword.length() / 4) + (question.length() / 4));
        durationLogger.addEventToMyTSKey(System.currentTimeMillis()-startTime);
        return response;
    }

    String getSearchResponseForChosenKeyword(String chosenKeyword,String chunkStrategy){
        Query q = new Query(chosenKeyword+" @chunkStrategy:{"+chunkStrategy+"*}").
                summarizeFields(35,4," and furthermore ","text").
                limit(0,1).
                dialect(2);
        // Execute the query
        List<Document> docs = jedis.ftSearch(KNEE_EXPERT_SEARCH_INDEX_NAME, q).getDocuments();
        return String.valueOf(docs.get(0).get("text"));
    }

    //one way to get at the text will be to store it in an indexed field in redis and
    //then search for matching tokens and return a few dozen tokens as surrounding context
    //This may be useful for use by an LLM in creating an enriched prompt which it then submits to itsself
    //It might be enough to provide the context needed to answer a question with more detail than usual
    void loadAllKneeAdviceTextIntoSingleRedisHash() {
        String allKneeDataKey = KNEE_EXPERT_SEARCH_KEY_PREFIX+"all";
        if (!jedis.exists(allKneeDataKey)) {
            Scanner fileScanner = null;
            String allText = "";
            try {
                fileScanner = new Scanner(new FileReader("src/main/resources/kneepain.txt"));
                while (fileScanner.hasNextLine()) {
                    allText += fileScanner.nextLine();
                }
            } catch (Throwable t) {
                t.printStackTrace();
                System.exit(0);
            }//This is a major issue, so we exit the program
            jedis.hset(allKneeDataKey, "text", allText);
            jedis.hset(allKneeDataKey,"chunkStrategy","none");//later we can filter on strategy
        }
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
