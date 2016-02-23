package org.cloudcoder.app.wizard.exec;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Scanner;

import org.cloudcoder.app.wizard.model.AWSRegion;
import org.cloudcoder.app.wizard.model.Document;
import org.cloudcoder.app.wizard.model.DocumentFactory;

import com.amazonaws.AmazonServiceException;
import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.SystemPropertiesCredentialsProvider;
import com.amazonaws.services.ec2.AmazonEC2Client;
import com.amazonaws.services.ec2.model.CreateKeyPairRequest;
import com.amazonaws.services.ec2.model.CreateKeyPairResult;
import com.amazonaws.services.ec2.model.CreateTagsRequest;
import com.amazonaws.services.ec2.model.CreateVpcRequest;
import com.amazonaws.services.ec2.model.CreateVpcResult;
import com.amazonaws.services.ec2.model.DescribeKeyPairsRequest;
import com.amazonaws.services.ec2.model.DescribeKeyPairsResult;
import com.amazonaws.services.ec2.model.DescribeVpcsResult;
import com.amazonaws.services.ec2.model.KeyPair;
import com.amazonaws.services.ec2.model.KeyPairInfo;
import com.amazonaws.services.ec2.model.Tag;
import com.amazonaws.services.ec2.model.Vpc;

// Currently hard-coded for Amazon AWS.
// Eventually, implement this for other cloud providers.
public class CloudService {
	private static final String CLOUDCODER_VPC_NAME = "cloudcoder-vpc";
	
	private Document document;
	
	private AWSCredentials credentials;
	private AmazonEC2Client client;
	
	private AWSInfo info;
	
	public void setDocument(Document document) {
		this.document = document;
		this.info = new AWSInfo();
	}
	
	public void login() throws ExecException {
		try {
			// Get AWS credentials, use them to set system properties
			System.setProperty("aws.accessKeyId", document.getValue("aws.accessKeyId").getString());
			System.setProperty("aws.secretKey", document.getValue("aws.secretAccessKey").getString());
			
			SystemPropertiesCredentialsProvider provider = new SystemPropertiesCredentialsProvider();
			this.credentials = provider.getCredentials();
			
			this.client = new AmazonEC2Client(credentials);
			AWSRegion region = document.getValue("awsRegion.region").getEnum(AWSRegion.class);
			client.setEndpoint(region.getEndpoint());
		} catch (AmazonServiceException e) {
			throw new ExecException("Failed to login to AWS", e);
		}
	}
	
	public void createOrFindVpc() throws ExecException {
		try {
			// See if there is an existing VPC
			Vpc cloudcoderVpc = null;
			DescribeVpcsResult vpcs = client.describeVpcs();
			for (Vpc vpc : vpcs.getVpcs()) {
				List<Tag> tags = vpc.getTags();
				//System.out.println("VPC id: " + vpc.getVpcId());
				for (Tag t : tags) {
					//System.out.printf("  Tag: key=%s, value=%s\n", t.getKey(), t.getValue());
					if (t.getKey().equals("Name") && t.getValue().equals(CLOUDCODER_VPC_NAME)) {
						cloudcoderVpc = vpc;
						break;
					}
				}
			}
			
			if (cloudcoderVpc != null) {
				System.out.println("Found " + CLOUDCODER_VPC_NAME + ", id=" + cloudcoderVpc.getVpcId());
			} else {
				// Create a VPC
				CreateVpcRequest req = new CreateVpcRequest("10.0.0.0/24");
				CreateVpcResult result = client.createVpc(req);
				cloudcoderVpc = result.getVpc();
				
				// Tag it with the correct name
				CreateTagsRequest tagReq = new CreateTagsRequest();
				tagReq.setTags(Arrays.asList(new Tag("Name", CLOUDCODER_VPC_NAME)));
				tagReq.setResources(Arrays.asList(cloudcoderVpc.getVpcId()));
				client.createTags(tagReq);
				System.out.printf("Tagged VPC %s with Name=%s\n", cloudcoderVpc.getVpcId(), CLOUDCODER_VPC_NAME);
			}
			
			info.setVpc(cloudcoderVpc);
		} catch (AmazonServiceException e) {
			throw new ExecException("Failed to login to enumerate VPCs/create new VPC", e);
		}
	}
	
	public void createOrChooseKeypair() throws ExecException {
		try {
			if (document.getValue("awsKeypair.useExisting").getBoolean()) {
				// Verify that chosen keypair filename matches the
				// name of existant keypair.  If so, load the key material
				// from a file and continue.
				
				String keyPairFilename = document.getValue("awsKeypair.filename").getString();
				String keyPairName = new File(keyPairFilename).getName();
				int ext = keyPairName.toLowerCase().lastIndexOf('.');
				if (ext >= 0) {
					keyPairName = keyPairName.substring(0, ext);
				}
				
				DescribeKeyPairsResult result = client.describeKeyPairs();
				List<KeyPairInfo> keyPairs = result.getKeyPairs();

				for (KeyPairInfo keyPairInfo : keyPairs) {
					if (keyPairInfo.getKeyName().equals(keyPairName)) {
						System.out.println("Found keypair " + keyPairName);
						this.info.setKeyPair(loadKeyPair(keyPairFilename, keyPairName));
						System.out.println("Loading key from file " + keyPairFilename);
						return;
					}
				}
				
				throw new ExecException("Could not find keypair " + keyPairName);
			} else {
				// Create a new keypair.
				CreateKeyPairRequest req = new CreateKeyPairRequest("cloudcoder-keypair");
				CreateKeyPairResult result = client.createKeyPair(req);
				this.info.setKeyPair(result.getKeyPair());
				System.out.println("Created keypair " + this.info.getKeyPair().getKeyName());
			}
		} catch (AmazonServiceException e) {
			throw new ExecException("Failed to find or create keypair", e);
		} catch (IOException e) {
			throw new ExecException("Failed to find or create keypair", e);
		}
	}
	
	private KeyPair loadKeyPair(String keyPairFilename, String keyName) throws IOException {
		byte[] data = Files.readAllBytes(Paths.get(keyPairFilename));
		String s = new String(data, Charset.forName("UTF-8"));
		KeyPair keyPair = new KeyPair();
		keyPair.setKeyName(keyName);
		keyPair.setKeyMaterial(s);
		// FIXME: is the signature important? Not sure we'll need it.
		return keyPair;
	}

	public static void main(String[] args) {
		@SuppressWarnings("resource")
		Scanner keyboard = new Scanner(System.in);
		System.out.print("Access key ID: ");
		String accessKeyId = keyboard.nextLine();
		System.out.print("Secret access key: ");
		String secretAccessKey = keyboard.nextLine();
		//System.out.print("Keypair filename: ");
		//String keyPairFilename = keyboard.nextLine();
		
		Document document = DocumentFactory.create();
		document.getValue("aws.accessKeyId").setString(accessKeyId);
		document.getValue("aws.secretAccessKey").setString(secretAccessKey);
		
		document.getValue("awsKeypair.useExisting").setBoolean(false);
		//document.getValue("awsKeypair.filename").setString(keyPairFilename);
		
		CloudService svc = new CloudService();
		svc.setDocument(document);
		try {
			svc.login();
			//svc.createOrFindVpc();
			svc.createOrChooseKeypair();
		} catch (ExecException e) {
			System.err.println("Error occurred");
			e.printStackTrace();
		}
	}
}
