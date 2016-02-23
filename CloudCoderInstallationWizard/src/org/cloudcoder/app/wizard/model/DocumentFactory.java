package org.cloudcoder.app.wizard.model;

import org.cloudcoder.app.wizard.model.validators.NoopValidator;
import org.cloudcoder.app.wizard.model.validators.StringValueNonemptyValidator;

public class DocumentFactory {
	public static Document create() {
		Document document = new Document();
		
		// Add pages
		Page welcomePage = new Page("welcome", "Welcome to the CloudCoder installation wizard");
		welcomePage.addHelpText("msg", "Welcome message");
		document.addPage(welcomePage);
		
		Page awsCredentialsPage = new Page("aws", "Enter your AWS credentials");
		awsCredentialsPage.addHelpText("msg", "Message");
		awsCredentialsPage.add(new StringValue("accessKeyId", "Access key ID"), StringValueNonemptyValidator.INSTANCE);
		awsCredentialsPage.add(new StringValue("secretAccessKey", "Secret access key"), StringValueNonemptyValidator.INSTANCE);
		document.addPage(awsCredentialsPage);
		
		Page awsRegionPage = new Page("awsRegion", "Choose an AWS region");
		awsRegionPage.addHelpText("msg", "Message");
		awsRegionPage.add(new EnumValue<AWSRegion>(AWSRegion.class, "region", "AWS EC2 Region"), new NoopValidator());
		document.addPage(awsRegionPage);
		
		Page keypairPage = new Page("awsKeypair", "Choose or create a keypair");
		keypairPage.addHelpText("msg", "Message");
		keypairPage.add(new BooleanValue("useExisting", "Use existing keypair"), new NoopValidator());
		keypairPage.add(new FilenameValue("name", "Existing keypair file"), new StringValueNonemptyValidator());
		document.addPage(keypairPage);
		
		return document;
	}
}
