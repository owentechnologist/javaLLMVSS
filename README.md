This example Java code shows the use of langchain4j as a library that enables interactions with popular Large Languages Models as well as the use of huggingFace libraries for embedding vectors.

A sample Tool is shown that uses RediSearch as a means to retrieve needed information.

A chat-bot demonstrates holding onto two different sessions - remembering user names.

The Main class accepts arguments that either run logic it holds that interacts with an LLM, or fires off one of teh other Classes in the package that demonstrates something related to embedding vectors and searching etc.

This code uses JedisPooled for the connection to Redis which allows all operations you might want - including the use of TimeSeries for accurate metrics and trend analysis as well as Probabilistic data structures such as CuckooFilters and TopK which allow for de-duping and lightweight-estimated counting/ranking.
Some information on starting the program is given at the top of the Main class - it is also below: 

     * Example:  mvn compile exec:java -Dexec.cleanupDaemonThreads=false -Dexec.args="-h redis-12000.homelab.local -p 12000"
     * Expects -h host and -p port
     * optional: -s password
     * optional: -u username
     * optional: -testmemory
     * optional: -searchtool
     * optional: -localembedding
     * optional: -vectorsearch
     * Example:  mvn compile exec:java -Dexec.cleanupDaemonThreads=false -Dexec.args="-h redis-12000.homelab.local -p 12000 -s password"
     * Example:  mvn compile exec:java -Dexec.cleanupDaemonThreads=false -Dexec.args="-testmemory true"


