package com.redislabs.sa.ot.jllmvss;

public class APIKEYS {
    public static String getOPENAIKEY(){
        return System.getenv("OPENAI_GPT_KEY");
    }
    public final static String getDemoKey(){
        return System.getenv("demo_key");
    }
}
