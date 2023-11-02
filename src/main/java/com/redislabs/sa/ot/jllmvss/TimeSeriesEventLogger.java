package com.redislabs.sa.ot.jllmvss;

import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.timeseries.TSCreateParams;

import java.util.HashMap;
import java.util.Map;

public class TimeSeriesEventLogger {

    static final String COUNTING_TOKENS_USED = "countingTokensUsed";
    static final String DURATION_IN_MILLISECONDS = "durationInMilliseconds";
    JedisPooled jedis = null;
    String tsKeyName = null;
    String customLabel = null; //countingTokensUsed,durationInMilliseconds
    String sharedLabel = "javaLLMVSS";
    boolean isReadyForEvents = false;

    public TimeSeriesEventLogger setCustomLabel(String label){
        this.customLabel = label;
        return this;
    }

    public TimeSeriesEventLogger initTS(){
        if((null==tsKeyName)||(null==jedis)){
            throw new RuntimeException("Must setJedis() and the TimeSeriesEventLogger.setTSKeyNameForMyLog()!");
        }
        Map<String,String> map = new HashMap<>();
        map.put("sharedLabel",sharedLabel);
        if(null!=customLabel) {
            map.put("customLabel", customLabel);
        }
        if(!jedis.exists(tsKeyName)) {
            jedis.tsCreate(tsKeyName, TSCreateParams.createParams().labels(map));
        }
        isReadyForEvents=true;
        return this;
    }

    public TimeSeriesEventLogger setJedis(JedisPooled jedis){
        this.jedis=jedis;
        return this;
    }

    public TimeSeriesEventLogger setTSKeyNameForMyLog(String tsKeyName){
        this.tsKeyName = tsKeyName;
        return this;
    }

    public void addEventToMyTSKey(double val){
        if((!isReadyForEvents)&&(!jedis.exists(tsKeyName))){
            initTS();
        }
        jedis.tsAdd(tsKeyName,val);
    }
}
