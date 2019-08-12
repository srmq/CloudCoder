package org.cloudcoder.progsnap2;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Scanner;

import org.apache.commons.io.IOUtils;
import org.cloudcoder.app.server.persist.Database;
import org.cloudcoder.app.server.submitsvc.IFutureSubmissionResult;
import org.cloudcoder.app.server.submitsvc.oop.OutOfProcessSubmitService;
import org.cloudcoder.app.shared.model.CompilationOutcome;
import org.cloudcoder.app.shared.model.CompilationResult;
import org.cloudcoder.app.shared.model.CompilerDiagnostic;
import org.cloudcoder.app.shared.model.Language;
import org.cloudcoder.app.shared.model.Problem;
import org.cloudcoder.app.shared.model.ProblemAndTestCaseList;
import org.cloudcoder.app.shared.model.SubmissionException;
import org.cloudcoder.app.shared.model.SubmissionResult;
import org.cloudcoder.app.shared.model.TestCase;
import org.cloudcoder.dataanalysis.Retest;
import org.cloudcoder.dataanalysis.Util;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import au.com.bytecode.opencsv.CSVReader;
import au.com.bytecode.opencsv.CSVWriter;

public class GatherCompileErrors {
	private static final Logger logger = LoggerFactory.getLogger(Retest.class);

	//private Map<Integer, Problem> problemMap;
	private Map<Integer, ProblemAndTestCaseList> problemMap;

	private File diagDir;
	
	public GatherCompileErrors() {
		problemMap = new HashMap<Integer, ProblemAndTestCaseList>();
	}
	
	public ProblemAndTestCaseList getProblem(int problemId) {
		ProblemAndTestCaseList p = problemMap.get(problemId);
		if (p == null) {
			p = new ProblemAndTestCaseList();
			
			// Load the Problem
			Problem prob = new Problem();
			prob.setProblemId(problemId);
			Database.getInstance().reloadModelObject(prob);
			p.setProblem(prob);
			
			// Load the test cases
			List<TestCase> testCases = Database.getInstance().getTestCasesForProblem(problemId);
			p.setTestCaseList(testCases);
			
			problemMap.put(problemId, p);
		}
		return p;
	}
	
	public void execute(String[] args) throws FileNotFoundException, IOException, InterruptedException {
		// Load global cloudcoder.properties (assume they are in parent directory)
		Properties globalConfig = new Properties();
		try (InputStream g = new FileInputStream("../cloudcoder.properties")){
			globalConfig.load(g);
		}
		OutOfProcessSubmitService svc = new OutOfProcessSubmitService();
		svc.initFromConfigProperties(globalConfig);
		svc.start();
		OutOfProcessSubmitService.setInstance(svc);
		
		Scanner keyboard = new Scanner(System.in);
		Properties config = Export.getExportConfig(args, keyboard);

		Util.connectToDatabase(config);
		
		String baseDir = config.getProperty("ps2.dest");
		
		// Create a directory where compiler diagnostics can be written
		this.diagDir = new File(baseDir, "diag");
		diagDir.mkdirs();
		
		try (FileInputStream fis = new FileInputStream(baseDir + "/MainTable.csv")) {
			InputStreamReader rd = new InputStreamReader(fis, StandardCharsets.UTF_8);
			@SuppressWarnings("resource")
			CSVReader r = new CSVReader(rd);
			
			// read header
			String[] header = r.readNext();
			
			// TODO: header for output CSV (add column(s) for source location, error text) 
			
			for (;;) {
				String[] row = r.readNext();
				if (row == null) { break; }
				Map<String, String> rowValues = new HashMap<String, String>();
				for (int i = 0; i < row.length; i++) {
					rowValues.put(header[i], row[i] != null ? row[i] : "");
				}
				
				if (rowValues.get("EventType").equals("Compile")) {
					int problemId = Integer.parseInt(rowValues.get("ProblemID"));
					ProblemAndTestCaseList p = getProblem(problemId);
					
					if (p.getProblem().getProblemType().getLanguage() == Language.C) {
						// Is a C problem!
						
						System.out.printf("Event %s is a C compilation\n", rowValues.get("EventID"));
						
						if (rowValues.get("ProgramResult").equals("Error")) {
							// This submission failed to compile!
							System.out.printf("  ==> Event %s is a failed C compilation\n", rowValues.get("EventID"));
							
							// Get the code
							String code;
							String codeFileName = baseDir + "/CodeStates/" + rowValues.get("CodeStateSection");
							System.out.printf("  Code file name is %s\n", codeFileName);
							try (InputStream in = new FileInputStream(codeFileName)) {
								code = IOUtils.toString(in);
							}
							
							// Submit the crud
							try {
								System.out.printf("  Recompiling event %s\n", rowValues.get("EventID"));
								IFutureSubmissionResult future = svc.submitAsync(p.getProblem(), p.getTestCaseData(), code);
								SubmissionResult result = null;
								do {
									result = future.waitFor(100L);
								} while (result == null);
								System.out.printf("  Successfully recompiled event %s\n", rowValues.get("EventID"));
								
								CompilationResult cr = result.getCompilationResult();
								if (cr.getOutcome() != CompilationOutcome.FAILURE) {
									logger.warn("Compilation outcome wasn't failure?");
								} else {
									String eventId = rowValues.get("EventID");
									File diagOut = new File(this.diagDir, eventId + ".csv");
									if (diagOut.exists()) {
										System.out.println("Diagnostic file for event " + eventId + " already exists");
									} else {
										File diagOutTmp = new File(this.diagDir, eventId + "-tmp.csv");
										try (FileOutputStream os = new FileOutputStream(diagOutTmp)) {
											OutputStreamWriter pw = new OutputStreamWriter(os, StandardCharsets.UTF_8);
											CSVWriter dw = new CSVWriter(pw);
											dw.writeNext("EventType", "ParentEventID", "CodeStateSection", "SourceLocation",
													"CompileMessageType", "CompileMessageData");
											
											for (CompilerDiagnostic d : cr.getCompilerDiagnosticList()) {
												String message = d.getMessage();
												String eventType = message.startsWith("error:") ? "Compile.Error" : "Compile.Warning";
												String compileMessageType = message.startsWith("error:") ? "Error" : "Warning";
												StringBuilder loc = new StringBuilder();
												loc.append("Text:");
												loc.append(d.getStartLine());
												if (d.getStartColumn() >= -1) {
													// Column information reported by gcc is 0-based,
													// convert to 1-based
													loc.append(":");
													loc.append(d.getStartColumn() + 1);
												}
												
												dw.writeNext(
														eventType,
														eventId,
														rowValues.get("CodeStateSection"),
														loc.toString(),
														compileMessageType,
														message);
											}
											
											dw.flush();
										}
										
										// We seem to have written the data successfully, so commit it
										// to the correct file name.
										Files.move(diagOutTmp.toPath(), diagOut.toPath(),
												StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
										
										System.out.printf("  Successfully recorded %d diagnostics for compile event %s\n ",
												cr.getCompilerDiagnosticList().length, eventId);
									}
									
									//cr.getCompilerDiagnosticList()
								}
							} catch (SubmissionException e) {
								logger.error("Exception sending submission for recompilation", e);
							}
						}
					}
				}
			}
		}
		
		svc.shutdown();
	}
	
	public static void main(String[] args) throws FileNotFoundException, IOException, InterruptedException {
		GatherCompileErrors gce = new GatherCompileErrors();
		gce.execute(args);
	}
}
