package org.cloudcoder.builder2.commandrunner;

import org.cloudcoder.app.shared.model.TestCase;
import org.cloudcoder.builder2.model.BuilderSubmission;
import org.cloudcoder.builder2.model.CommandInput;
import org.cloudcoder.builder2.model.IBuildStep;
import org.cloudcoder.builder2.model.InternalBuilderException;

/**
 * Create a {@link CommandInput} with the input from each {@link TestCase}.
 * 
 * @author David Hovemeyer
 */
public class CreateCommandInputsForEachTestCaseBuildStep implements IBuildStep {

	@Override
	public void execute(BuilderSubmission submission) {
		TestCase[] testCaseList = submission.getArtifact(TestCase[].class);
		if (testCaseList == null) {
			throw new InternalBuilderException(this.getClass(), "No TestCase list");
		}
		
		CommandInput[] commandInputList = new CommandInput[testCaseList.length];
		for (int i = 0; i < testCaseList.length; i++) {
			commandInputList[i] = new CommandInput(testCaseList[i].getInput());
		}
		submission.addArtifact(commandInputList);
	}

}