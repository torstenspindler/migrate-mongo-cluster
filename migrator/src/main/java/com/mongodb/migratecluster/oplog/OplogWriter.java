package com.mongodb.migratecluster.oplog;

import com.mongodb.MongoBulkWriteException;
import com.mongodb.MongoClient;
import com.mongodb.bulk.BulkWriteResult;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.*;
import com.mongodb.migratecluster.AppException;
import com.mongodb.migratecluster.commandline.ApplicationOptions;
import com.mongodb.migratecluster.commandline.ResourceFilter;
import com.mongodb.migratecluster.helpers.MongoDBHelper;
import com.mongodb.migratecluster.model.Resource;
import com.mongodb.migratecluster.predicates.CollectionFilterPredicate;
import com.mongodb.migratecluster.predicates.DatabaseFilterPredicate;
import com.mongodb.migratecluster.trackers.OplogTimestampTracker;
import com.mongodb.migratecluster.trackers.WritableDataTracker;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * File: OplogWriter
 * Author: Shyam Arjarapu
 * Date: 1/14/19 7:20 AM
 * Description:
 *
 * A class to help write the apply the oplog entries on the target
 */
public class OplogWriter {
    private final MongoClient oplogStoreClient;
    private final MongoClient targetClient;
    private final String reader;
    private final Resource oplogTrackerResource;
    private final HashMap<String, Boolean> allowedNamespaces;

    final static Logger logger = LoggerFactory.getLogger(OplogWriter.class);
    private final DatabaseFilterPredicate databasePredicate;
    private final CollectionFilterPredicate collectionPredicate;
	private Map<String, String> renames;

    public OplogWriter(MongoClient targetClient, MongoClient oplogStoreClient, String reader, ApplicationOptions options) {
        this.targetClient = targetClient;
        this.oplogStoreClient = oplogStoreClient;
        this.reader = reader;
        oplogTrackerResource = new Resource("migrate-mongo", "oplog.tracker");
        allowedNamespaces = new HashMap<>();

        List<ResourceFilter> blacklistFilter = options.getBlackListFilter();
        databasePredicate = new DatabaseFilterPredicate(blacklistFilter);
        collectionPredicate = new CollectionFilterPredicate(blacklistFilter);
        renames = options.getRenames();
    }

    /**
     * Applies the oplog documents on the oplog store
     *
     * @param operations a list of oplog operation documents
     * @throws AppException
     */
    public int applyOperations(List<Document> operations) throws AppException {
        int totalModelsAdded = 0;
        int totalValidOperations = 0;
        String previousNamespace = null;
        Document previousDocument = null;
        List<WriteModel<Document>> models = new ArrayList<>();

        for(int i = 0; i < operations.size(); i++) {
            Document currentDocument = operations.get(i);
            String currentNamespace = currentDocument.getString("ns");
            
            logger.debug("Old namespace: {}", currentNamespace);
            currentNamespace = renameNamespace(currentNamespace);
            logger.debug("New namespace: {}", currentNamespace);
            
            if (!isNamespaceAllowed(currentNamespace)) {
                continue;
            }
            if (!currentNamespace.equals(previousNamespace)) {
                // change of namespace. bulk apply models for previous namespace
                if (previousNamespace != null && models.size() > 0) {
                    BulkWriteResult bulkWriteResult = applyBulkWriteModelsOnCollection(previousNamespace, models);
                    if (bulkWriteResult != null) {
                        totalModelsAdded += bulkWriteResult.getDeletedCount() + bulkWriteResult.getModifiedCount() + bulkWriteResult.getInsertedCount();
                    }
                    models.clear();
                    // save documents timestamp to oplog tracker
                    saveTimestampToOplogStore(previousDocument);
                }
                previousNamespace = currentNamespace;
                previousDocument = currentDocument;
            }
            WriteModel<Document> model = getWriteModelForOperation(currentDocument);
            if (model != null) {
                models.add(model);
                totalValidOperations++;
            }
            else {
                // if the command is $cmd for create index or create collection, there would not be any write model.
                logger.info(String.format("could not convert the document to model. Give document is [%s]", currentDocument.toJson()));
            }
        }

        if (models.size() > 0) {
            BulkWriteResult bulkWriteResult = applyBulkWriteModelsOnCollection(previousNamespace, models);
            if (bulkWriteResult != null) {
                totalModelsAdded += bulkWriteResult.getDeletedCount() + bulkWriteResult.getModifiedCount() + bulkWriteResult.getInsertedCount();

                // save documents timestamp to oplog tracker
                saveTimestampToOplogStore(previousDocument);
            }
        }

        if (totalModelsAdded != totalValidOperations) {
            logger.warn("total models added {} is not equal to operations injected {}", totalModelsAdded, operations.size());
        }

        return totalModelsAdded;
    }

	private String renameNamespace(String currentNamespace) {
		// hope that's the correct place to alter the namespace
		// brute forcing renames into this
		String splitNamespace[] = currentNamespace.split("\\.");
		String databaseName = "";
		String collectionName = "";
		logger.debug("splitNamespace.length: {}", splitNamespace.length);
		if (splitNamespace.length > 0) {
			logger.debug("splitNamespace[0]: {}", splitNamespace[0]);
		}
		if (splitNamespace.length == 1) {
			databaseName = splitNamespace[0];
		} else if (splitNamespace.length > 1) {
			databaseName = splitNamespace[0];
			collectionName = currentNamespace.substring(databaseName.length()+1);
		}
		if (renames.containsKey(databaseName)) {
			databaseName = renames.get(databaseName);
			logger.debug("Replacing database name {}", databaseName);
		}
		if (renames.containsKey(collectionName)) {
			collectionName = renames.get(collectionName);
			logger.debug("Repacing collection name {}", collectionName);
		}
		if (!databaseName.equals("")) {
			currentNamespace = databaseName + "." + collectionName;
		}
		return currentNamespace;
	}

    private boolean isNamespaceAllowed(String namespace) {
        if (!allowedNamespaces.containsKey(namespace))
        {
            boolean allow = checkIfNamespaceIsAllowed(namespace);
            allowedNamespaces.put(namespace, allow);
        }
        // return cached value
        return allowedNamespaces.get(namespace);
    }

    private boolean checkIfNamespaceIsAllowed(String namespace) {
        String databaseName = namespace.split("\\.")[0];
        try {
            Document dbDocument = new Document("name", databaseName);
            boolean isNotBlacklistedDB = databasePredicate.test(dbDocument);
            if (isNotBlacklistedDB) {
                // check for collection as well
                String collectionName = namespace.substring(databaseName.length()+1);
                Resource resource = new Resource(databaseName, collectionName);
                return collectionPredicate.test(resource);
            }
            else {
                return false;
            }
        } catch (Exception e) {
            logger.error("error while testing the namespace is in black list or not");
            return false;
        }
    }

    private BulkWriteResult applyBulkWriteModelsOnCollection(String namespace,
                                 List<WriteModel<Document>> operations)  throws AppException {
        MongoCollection<Document> collection = MongoDBHelper.getCollectionByNamespace(this.targetClient, namespace);
        try{
            return applyBulkWriteModelsOnCollection(collection, operations);
        }
        catch (MongoBulkWriteException err) {
            if (err.getWriteErrors().size() == operations.size()) {
                // every doc in this batch is error. just move on
                return null;
            }
            logger.warn("bulk write of oplog entries failed. applying oplog operations one by one");
            BulkWriteResult bulkWriteResult = null;
            for (WriteModel<Document> op : operations) {
                List<WriteModel<Document>> soloBulkOp = new ArrayList<>();
                soloBulkOp.add(op);
                try {
                    bulkWriteResult = applyBulkWriteModelsOnCollection(collection, soloBulkOp);
                } catch (Exception soloErr) {
                    // do nothing
                }
            }
            return bulkWriteResult;
        }
        catch (Exception ex) {
            logger.warn("bulk write of oplog entries failed. doing one by one now");
        }
        return null;
    }

    private BulkWriteResult applyBulkWriteModelsOnCollection(MongoCollection<Document> collection, List<WriteModel<Document>> operations) throws AppException {
        BulkWriteResult writeResult = MongoDBHelper.performOperationWithRetry(
                () -> {
                    BulkWriteOptions options = new BulkWriteOptions();
                    options.ordered(true);
                    return collection.bulkWrite(operations, options);
                }
                , new Document("operation", "bulkWrite"));
        return writeResult;
    }

    /**
     * Get's a WriteModel for the given oplog operation
     *
     * @param operation an oplog operation
     * @return a WriteModel of a bulk operation
     */
    private WriteModel<Document> getWriteModelForOperation(Document operation)  throws AppException {
        String message;
        WriteModel<Document> model = null;
        switch (operation.getString("op")){
            case "i":
                model = getInsertWriteModel(operation);
                break;
            case "u":
                model = getUpdateWriteModel(operation);
                break;
            case "d":
                model = getDeleteWriteModel(operation);
                break;
            case "c":
                // might have to be individual operation
                performRunCommand(operation);
                //TODO performRunCommand
                // update the last timestamp on oplogStore
                break;
            case "n":
                break;
            default:
                message = String.format("unsupported operation %s; op: %s", operation.getString("op"), operation.toJson());
                logger.error(message);
                throw new AppException(message);
        }
        return model;
    }

    private WriteModel<Document> getInsertWriteModel(Document operation) {
        Document document = operation.get("o", Document.class);
        return new InsertOneModel<>(document);
    }

    private WriteModel<Document>  getUpdateWriteModel(Document operation) throws AppException {
        Document find = operation.get("o2", Document.class);
        Document update = operation.get("o", Document.class);

        return new UpdateOneModel<>(find, update);
    }

    private WriteModel<Document>  getDeleteWriteModel(Document operation) throws AppException {
        Document find = operation.get("o", Document.class);
        return new DeleteOneModel<>(find);
    }

    private void performRunCommand(Document operation) throws AppException {
        Document document = operation.get("o", Document.class);
        String databaseName = operation.getString("ns").replace(".$cmd", "");
        
        if (renames.containsKey(databaseName)) {
        	databaseName = renames.get(databaseName);
        	// brute forcing our way into ns
//        	document.put("ns", databaseName + ".$cmd");
        	operation.put("ns", databaseName + ".$cmd");
        	if (document.containsKey("drop")) {
        		if (renames.containsKey(document.get("drop"))) {
        			document.put("drop", renames.get(document.get("drop")));
        		}
        	}
        	if (document.containsKey("create")) {
        		if (renames.containsKey(document.get("create"))) {
        			document.put("create", renames.get(document.get("create")));
        		}
        	}
        	if (document.containsKey("idIndex")) {
        		Document index = document.get("idIndex", Document.class);
        		String namespace = renameNamespace(index.getString("ns"));
        		logger.debug("idIndex new namespace: {}", namespace);
        		index.put("ns", namespace);
        	}
        }
        logger.debug("performRunCommand: {}", databaseName);
        logger.debug("performRunCommand, modified operation: {}", operation);
        MongoDatabase database = MongoDBHelper.getDatabase(this.targetClient, databaseName);
        MongoDBHelper.performOperationWithRetry(() -> {
            database.runCommand(document);
            return 1L;
        }, operation);

        String message = String.format("completed runCommand op on database: %s; document: %s", databaseName, operation.toJson());
        logger.debug(message);
    }

    /**
     * Save's a document as the lastest oplog timestamp on oplog store
     *
     * @param document a document representing the fields that need to be set
     */
    protected void saveTimestampToOplogStore(Document document) {
        WritableDataTracker tracker = new OplogTimestampTracker(oplogStoreClient, oplogTrackerResource, this.reader);
        tracker.updateLatestDocument(document);
    }
}
