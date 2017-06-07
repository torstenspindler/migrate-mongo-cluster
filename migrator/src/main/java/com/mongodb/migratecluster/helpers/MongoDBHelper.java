package com.mongodb.migratecluster.helpers;

import com.mongodb.*;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.migratecluster.AppException;
import com.mongodb.migratecluster.Application;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Supplier;

/**
 * File: MongoDBHelper
 * Author: shyam.arjarapu
 * Date: 4/29/17 10:08 PM
 * Description:
 */
public  class MongoDBHelper {
    private final static Logger logger = LoggerFactory.getLogger(MongoDBHelper.class);

    public static MongoDatabase getDatabase(MongoClient client, String databaseName) {
       MongoDatabase database = client.getDatabase(databaseName);
        return database;
    }

    public static MongoCollection<Document> getCollection(MongoClient client, String databaseName, String collectionName) {
        MongoDatabase database = getDatabase(client, databaseName);
        MongoCollection<Document> collection = database.getCollection(collectionName);
        return collection;
    }

    public static <T> T performMongoOperationWithRetry(Supplier<T> operationFunc, Document operation) throws AppException {
        for(int retry = 0; retry <= 1; retry++) {
            try{
                return operationFunc.get();
            }
            catch (MongoNotPrimaryException | MongoSocketException | MongoNodeIsRecoveringException me){
                // do a retry
                // continue; // commented because continue is implicit
                logger.error("[RETRY] Failed to perform operation. Will retry once; op: {}; error: {}",
                        operation.toJson(), me.toString());
            }
            catch (MongoWriteException we) {
                if (we.getMessage().startsWith("E11000 duplicate key error collection")) {
                    logger.warn("[IGNORE] Duplicate key exception while performing operation: {}; error: {}",
                            operation.toJson(), we.toString());
                    return null;
                }
                logger.error("error while performing operation: {}; error: {}", operation.toJson(), we.toString());
                throw we;
            }
            catch (Exception e) {
                logger.error("[UNHANDLED] error while performing operation: {}; error: {}", operation.toJson(), e.toString());
                throw e;
            }
        }
        return null;
    }

}