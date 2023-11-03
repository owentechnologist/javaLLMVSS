package com.redislabs.sa.ot.jllmvss;

import redis.clients.jedis.search.Query;
import redis.clients.jedis.search.Query.Filter;


public class DebugRedisSearchJedisQuery {
    Query q = null;
    String debugQueryString = "*";
    String debugReturnFields = "";
    String debugParametersString = "";
    String debugLimitString = "";
    String debugFilterString = "";
    int paramAddedCounter=0;
    String[][] paramsAndValues = new String[20][20];//unlikely we will need more than 20 params

    public String toString(){
        return this.debugQueryString+debugFilterString+buildParametersString()+debugReturnFields+debugLimitString;
    }

    public Query limit(Integer offset, Integer limit) {
        this.debugLimitString = " LIMIT "+offset+" "+limit;
        q.limit(offset, limit);
        return q;
    }

    public Query returnFields(String... fields) {
        for(String f:fields){
            this.debugReturnFields+=" "+f;
        }
        String debugReturnFieldsPrefix = " RETURN "+fields.length;
        this.debugReturnFields=debugReturnFieldsPrefix+debugReturnFields;
        q.returnFields(fields);
        return q;
    }

    String buildParametersString(){
        this.debugParametersString=" PARAMS "+paramAddedCounter+" ";
        for(int x=0;x<paramAddedCounter;x++){
            debugParametersString+=paramsAndValues[x][0]+" ";
            debugParametersString+=paramsAndValues[x][1]+" ";
        }
        return debugParametersString;
    }

    public Query addParam(String name, Object value) {
        System.out.println("addParam() called with "+name+" "+value.toString());
        paramsAndValues[paramAddedCounter][0]=name;
        paramsAndValues[paramAddedCounter][1]="\""+value.toString()+"\"";
        paramAddedCounter++;
        System.out.println(debugParametersString);
        q.addParam(name, value);
        return q;
    }

    public Query setSortBy(String field, boolean ascending) {
        this.debugQueryString = this.debugQueryString+" SORTBY @"+field+" "+ascending;
        q.setSortBy(field, ascending);
        return q;
    }

    public Query addFilter(Filter f) {
        this.debugFilterString=" "+f.toString()+" ";
        q.addFilter(f);
        return q;
    }

    /**
     * Create a new query to execute against an index
     *
     * @param queryString the textual part of the query
     */
    public Query startQuery(String queryString) {
        this.q = new Query(queryString);
        debugQueryString = queryString;
        return q;
    }
}
