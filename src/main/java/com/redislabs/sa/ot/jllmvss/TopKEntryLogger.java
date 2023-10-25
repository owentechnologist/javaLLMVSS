package com.redislabs.sa.ot.jllmvss;

import redis.clients.jedis.JedisPooled;
import java.util.Map;

public class TopKEntryLogger {

    boolean isReadyForEntries=false;
    String topKKeyName="topk:popularPrompts";//can be overridden for each instance of this class
    JedisPooled jedis = null;
    int topKSize = 5;

    public TopKEntryLogger setTopKSize(int size){
        this.topKSize = size;
        return this;
    }

    public TopKEntryLogger initTopK(){
        if((null==topKKeyName)||(null==jedis)){
            throw new RuntimeException("Must setJedis() and you may want to call the TopKEntryLogger.setTopKKeyNameForMyLog()!");
        }
        jedis.topkReserve(topKKeyName, topKSize, 2000, 7, 0.925);

        if(!jedis.exists(topKKeyName)) {

        }
        isReadyForEntries=true;
        return this;
    }

    public TopKEntryLogger setJedis(JedisPooled jedis){
        this.jedis=jedis;
        return this;
    }

    public TopKEntryLogger setTopKKeyNameForMyLog(String tkKeyName){
        this.topKKeyName = tkKeyName;
        return this;
    }

    public void addEntryToMyTopKKey(String val){
        if((!isReadyForEntries)&&(!jedis.exists(topKKeyName))){
            initTopK();
        }
        jedis.topkAdd(topKKeyName,val);
    }

    public Map<String, Long> getTopKlistWithCount(boolean doPrintToScreen){
        Map<String,Long> listWithCount = jedis.topkListWithCount(topKKeyName);
        if(doPrintToScreen) {
            listWithCount.forEach((item, count) -> {
                System.out.println("EntryValue: "+item+" EntryCount: "+count);
            });
        }
        return listWithCount;
    }

}
