package org.recap.camel.accessionreconciliation;

import com.amazonaws.services.s3.AmazonS3;
import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.recap.RecapConstants;
import org.recap.RecapCommonConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Scope;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Created by akulak on 16/5/17.
 */
@Service
@Scope("prototype")
public class AccessionReconciliationProcessor {

    private static final Logger logger = LoggerFactory.getLogger(AccessionReconciliationProcessor.class);

    @Autowired
    CamelContext camelContext;

    @Autowired
    RestTemplate restTemplate;

    @Value("${scsb.solr.doc.url}")
    private String solrSolrClientUrl;

    @Value("${accession.reconciliation.filePath}")
    private String accessionFilePath;

    @Autowired
    AmazonS3 awsS3Client;

    private String institutionCode;
    private String imsLocationCode;

    int noOfLinesInFile=0;

    /**
     * Instantiates a new Accession reconcilation processor.
     *
     * @param institutionCode the institution code
     */
    public AccessionReconciliationProcessor(String institutionCode, String imsLocationCode) {
        this.institutionCode = institutionCode;
        this.imsLocationCode = imsLocationCode;
    }

    /**
     * Process input for accession reconcilation report.
     *
     * @param exchange the exchange
     */
    public void processInput(Exchange exchange) {
        HashMap<String,String> barcodesAndCustomerCodes=new HashMap<>();
        ArrayList<BarcodeReconcilitaionReport> barcodeReconcilitaionReportArrayList = exchange.getIn().getBody(ArrayList.class);
        for (BarcodeReconcilitaionReport barcodeReconcilitaionReport : barcodeReconcilitaionReportArrayList) {
           barcodesAndCustomerCodes.put(barcodeReconcilitaionReport.getBarcode(),barcodeReconcilitaionReport.getCustomerCode());
        }
        Integer index = (Integer) exchange.getProperty(RecapConstants.CAMEL_SPLIT_INDEX);
        HttpEntity httpEntity = new HttpEntity(barcodesAndCustomerCodes);
        ResponseEntity<Map> responseEntity = restTemplate.exchange(solrSolrClientUrl+ RecapConstants.ACCESSION_RECONCILATION_SOLR_CLIENT_URL, HttpMethod.POST, httpEntity,Map.class);
        Map<String,String> body = responseEntity.getBody();
        String barcodesAndCustomerCodesForReportFile = body.entrySet().stream().map(Object::toString).collect(Collectors.joining("\n")).replaceAll("=","\t");
        byte[] barcodesAndCustomerCodesForReportFileBytes =barcodesAndCustomerCodesForReportFile.getBytes(StandardCharsets.UTF_8);
        SimpleDateFormat simpleDateFormat = new SimpleDateFormat(RecapConstants.BARCODE_RECONCILIATION_FILE_DATE_FORMAT);
        try {
            String line= RecapConstants.NEW_LINE;
            byte[] newLine=line.getBytes(StandardCharsets.UTF_8);
            Path filePath = Paths.get(accessionFilePath+RecapCommonConstants.PATH_SEPARATOR+imsLocationCode+RecapCommonConstants.PATH_SEPARATOR+institutionCode+RecapCommonConstants.PATH_SEPARATOR+ RecapConstants.ACCESSION_RECONCILATION_FILE_NAME+imsLocationCode+institutionCode+simpleDateFormat.format(new Date())+".csv");
            if (!filePath.toFile().exists()) {
                Files.createDirectories(filePath.getParent());
                Files.createFile(filePath);
                logger.info("Accession Reconciliation File Created {} ",filePath);
            }
            if(filePath.toFile().exists()){
                noOfLinesInFile= Files.readAllLines(filePath).size();
            }
            if(index == 0){
                ArrayList<String> headerSet = new ArrayList<>();
                headerSet.add(RecapConstants.ACCESSION_RECONCILIATION_HEADER+ RecapConstants.TAB+ RecapConstants.CUSTOMER_CODE_HEADER);
                Files.write(filePath,headerSet, StandardOpenOption.APPEND);
            }
            else if (index > 0 && body.size()>0 && noOfLinesInFile>1){
                Files.write(filePath,newLine,StandardOpenOption.APPEND);
            }
            if(body.size()>0) {
                Files.write(filePath,barcodesAndCustomerCodesForReportFileBytes,StandardOpenOption.APPEND);
            }
        }
        catch (Exception e){
            logger.error(RecapCommonConstants.LOG_ERROR ,e);
        }
        startFileSystemRoutesForAccessionReconciliation(exchange,index);
        String xmlFileName = exchange.getIn().getHeader(RecapConstants.CAMEL_AWS_KEY).toString();
        String bucketName = exchange.getIn().getHeader("CamelAwsS3BucketName").toString();
        if (awsS3Client.doesObjectExist(bucketName, xmlFileName)) {
            String basepath = xmlFileName.substring(0, xmlFileName.lastIndexOf('/'));
            String fileName = xmlFileName.substring(xmlFileName.lastIndexOf('/'));
            awsS3Client.copyObject(bucketName, xmlFileName, bucketName, basepath + "/.done-" + imsLocationCode + "-" + institutionCode + fileName);
            awsS3Client.deleteObject(bucketName, xmlFileName);
        }
    }

    private void startFileSystemRoutesForAccessionReconciliation(Exchange exchange,Integer index) {
        if ((boolean)exchange.getProperty(RecapConstants.CAMEL_SPLIT_COMPLETE)){
            logger.info("split last index-->{}",index);
            try {
                logger.info("Starting {}{}{}",imsLocationCode, institutionCode, RecapConstants.ACCESSION_RECONCILIATION_FS_ROUTE_ID);
                camelContext.getRouteController().startRoute(imsLocationCode+institutionCode+RecapConstants.ACCESSION_RECONCILIATION_FS_ROUTE_ID);
            } catch (Exception e) {
                logger.error(RecapCommonConstants.LOG_ERROR, e);
            }
        }
    }

}
