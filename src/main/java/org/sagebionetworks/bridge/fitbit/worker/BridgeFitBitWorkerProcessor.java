package org.sagebionetworks.bridge.fitbit.worker;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;

import javax.annotation.Resource;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.base.Stopwatch;
import com.google.common.util.concurrent.RateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import org.sagebionetworks.bridge.file.FileHelper;
import org.sagebionetworks.bridge.fitbit.bridge.BridgeHelper;
import org.sagebionetworks.bridge.fitbit.bridge.FitBitUser;
import org.sagebionetworks.bridge.fitbit.schema.EndpointSchema;
import org.sagebionetworks.bridge.fitbit.util.Utils;
import org.sagebionetworks.bridge.rest.model.Study;
import org.sagebionetworks.bridge.sqs.PollSqsWorkerBadRequestException;
import org.sagebionetworks.bridge.worker.ThrowingConsumer;

/** Worker consumer for the FitBit Worker. This is called by BridgeWorkerPlatform and is the main entry point. */
@Component("FitBitWorker")
public class BridgeFitBitWorkerProcessor implements ThrowingConsumer<JsonNode> {
    private static final Logger LOG = LoggerFactory.getLogger(BridgeFitBitWorkerProcessor.class);

    private static final int REPORTING_INTERVAL = 10;
    static final String REQUEST_PARAM_DATE = "date";

    private final RateLimiter perUserRateLimiter = RateLimiter.create(1.0);

    private BridgeHelper bridgeHelper;
    private List<EndpointSchema> endpointSchemas;
    private FileHelper fileHelper;
    private TableProcessor tableProcessor;
    private UserProcessor userProcessor;

    /** Bridge Helper */
    @Autowired
    public final void setBridgeHelper(BridgeHelper bridgeHelper) {
        this.bridgeHelper = bridgeHelper;
    }

    /** Endpoint Schemas */
    @Resource(name = "endpointSchemas")
    public final void setEndpointSchemas(List<EndpointSchema> endpointSchemas) {
        this.endpointSchemas = endpointSchemas;
    }

    /** File Helper */
    @Autowired
    public final void setFileHelper(FileHelper fileHelper) {
        this.fileHelper = fileHelper;
    }

    /** Set rate limit, in users per second. */
    public final void setPerUserRateLimit(double rate) {
        perUserRateLimiter.setRate(rate);
    }

    /** Table Processor */
    @Autowired
    public final void setTableProcessor(TableProcessor tableProcessor) {
        this.tableProcessor = tableProcessor;
    }

    /** User Processor */
    @Autowired
    public final void setUserProcessor(UserProcessor userProcessor) {
        this.userProcessor = userProcessor;
    }

    /** This is the main entry point into the FitBit Worker. */
    @Override
    public void accept(JsonNode jsonNode) throws IOException, PollSqsWorkerBadRequestException {
        // Get request args.
        JsonNode dateNode = jsonNode.get(REQUEST_PARAM_DATE);
        if (dateNode == null || dateNode.isNull()) {
            throw new PollSqsWorkerBadRequestException("date must be specified");
        }
        String dateString = dateNode.textValue();

        LOG.info("Received request for date " + dateString);
        Stopwatch requestStopwatch = Stopwatch.createStarted();
        List<Study> studyList = bridgeHelper.getAllStudies();
        for (Study oneStudy : studyList) {
            String studyId = oneStudy.getIdentifier();
            if (Utils.isStudyConfigured(oneStudy)) {
                LOG.info("Processing study " + studyId);
                Stopwatch studyStopwatch = Stopwatch.createStarted();
                try {
                    processStudy(dateString, oneStudy);
                } catch (Exception ex) {
                    LOG.error("Error processing study " + studyId + ": " + ex.getMessage(), ex);
                } finally {
                    LOG.info("Finished processing study " + studyId + " in " + studyStopwatch.elapsed(TimeUnit.SECONDS)
                            + " seconds");
                }
            } else {
                LOG.info("Skipping study " + studyId);
            }
        }
        LOG.info("Finished processing request for date " + dateString + " in " +
                requestStopwatch.elapsed(TimeUnit.SECONDS) + " seconds");
    }

    // Visible for testing
    void processStudy(String dateString, Study study) {
        String studyId = study.getIdentifier();

        // Set up request context
        File tmpDir = fileHelper.createTempDir();
        try {
            RequestContext ctx = new RequestContext(dateString, study, tmpDir);

            // Get list of users (and their keys)
            Iterable<FitBitUser> fitBitUserIter = bridgeHelper.getFitBitUsersForStudy(study.getIdentifier());
            LOG.info("Processing users in study " + studyId);
            int numUsers = 0;
            Stopwatch userStopwatch = Stopwatch.createStarted();
            for (FitBitUser oneUser : fitBitUserIter) {
                perUserRateLimiter.acquire();

                // Call and process endpoints.
                for (EndpointSchema oneEndpointSchema : endpointSchemas) {
                    try {
                        userProcessor.processEndpointForUser(ctx, oneUser, oneEndpointSchema);
                    } catch (Exception ex) {
                        LOG.error("Error processing user for healthCode " + oneUser.getHealthCode() + " on endpoint " +
                                oneEndpointSchema.getEndpointId() + ": " + ex.getMessage(), ex);
                    }
                }

                // Reporting
                numUsers++;
                if (numUsers % REPORTING_INTERVAL == 0) {
                    LOG.info("Processing users in progress: " + numUsers + " users in " +
                            userStopwatch.elapsed(TimeUnit.SECONDS) + " seconds");
                }
            }
            LOG.info("Finished processing users: " + numUsers + " users in " +
                    userStopwatch.elapsed(TimeUnit.SECONDS) + " seconds");

            // Process and upload each table
            for (PopulatedTable onePopulatedTable : ctx.getPopulatedTablesById().values()) {
                String tableId = onePopulatedTable.getTableId();
                LOG.info("Processing table " + tableId);
                Stopwatch tableStopwatch = Stopwatch.createStarted();
                try {
                    tableProcessor.processTable(ctx, onePopulatedTable);
                } catch (Exception ex) {
                    LOG.error("Error processing table " + tableId + ": " + ex.getMessage(), ex);
                } finally {
                    LOG.info("Finished processing table " + tableId + " in " +
                            tableStopwatch.elapsed(TimeUnit.SECONDS) + " seconds");
                }
            }
        } finally {
            fileHelper.deleteDir(tmpDir);
        }
    }
}
