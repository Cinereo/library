package amtrackservice.server;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.GregorianCalendar;
import java.util.HashMap;

import amtrackservice.client.CalculationService;
import amtrackservice.client.Logger;

import com.google.gwt.user.server.rpc.RemoteServiceServlet;

/**
 * The server side implementation of the RPC service.
 */
@SuppressWarnings("serial")
public class CalculationServiceImpl extends RemoteServiceServlet implements
		CalculationService {
	
	private GregorianCalendar cal;
	
	private HashMap<Long, Process> processPool = new HashMap<Long, Process>();
	
	/**
	 * TODO needs to be rewritten
	 */
	public long startCalculation(HashMap<String, String> input,
			String wrapperPath, String functionName)
			throws IllegalArgumentException {
		
		System.out.println("Server side @startCalculation: wrapperPath: " + wrapperPath + " functionName " + functionName);
		
		String db_ip="localhost";
		String db_name=getServletContext().getInitParameter("dbname");
		String db_user=getServletContext().getInitParameter("dbuser");
		String db_pass=getServletContext().getInitParameter("dbpass");
		
		CalculationDB db = new CalculationDB(db_ip, db_name, db_user, db_pass);
		cal = new GregorianCalendar();

		long time = cal.getTimeInMillis();
		File file = new File(wrapperPath);
		
		if( !file.exists() ){			
			System.out.println("Server side @startCalculation: File " + wrapperPath + " missing");
			return -1;
		}
		
		String resultPath = file.getParentFile().getAbsolutePath() + time;

		StringBuffer fileText = new StringBuffer("");
		for (String s : input.keySet()) {
			fileText.append(s + ":" + input.get(s) + "\n");
		}
		try {
			CalculationFile.writeData(new String(fileText), resultPath);
		} catch (FileNotFoundException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		}

		ProcessBuilder pb = new ProcessBuilder(wrapperPath, resultPath);
		
		// added path to shared library, same as directory containg wrappers
		pb.environment().put("LD_LIBRARY_PATH", file.getParent());
		
		try {
			Process p = pb.start();			
			
			System.out.println("Server side @startCalculation: starting wrapper " + wrapperPath + " with argument " + resultPath);
			
			FileOutputStream stdoutFile = new FileOutputStream( resultPath + ".stdout");
			BufferedReader stdoutStream = new BufferedReader(new InputStreamReader(p.getInputStream()));
			String line = "";
			while ((line = stdoutStream.readLine()) != null) {
				System.out.println("Server side @startCalculation: stdout = " + line);
				stdoutFile.write(line.getBytes());
			}
			FileOutputStream stderrFile = new FileOutputStream( resultPath + ".stderr");
			BufferedReader stderrStream = new BufferedReader(new InputStreamReader(p.getErrorStream()));
			line = "";
			while ((line = stderrStream.readLine()) != null) {
				System.out.println("Server side @startCalculation: stderr = " + line);
				stderrFile.write(line.getBytes());
			}
			FileOutputStream exitCodeFile = new FileOutputStream( resultPath + ".exitcode");
			try {
				String exitCodeString = String.valueOf(p.exitValue());	
				exitCodeFile.write(exitCodeString.getBytes());
				System.out.println("Server side @startCalculation: exit code = " + exitCodeString);
			} catch(java.lang.IllegalThreadStateException e1) {
				System.out.println("Server side @startCalculation: Process not yet finished.");				
				String exitCodeString = " ";	
				exitCodeFile.write(exitCodeString.getBytes());
			}
			exitCodeFile.close();
//			stdoutFile.close();
//			stderrFile.close();
			processPool.put(time, p);
			db.insertCalculation(time, resultPath, functionName);
			System.out.println("Server side @startCalculation: inserted into DB " + time + " and " + resultPath);
		} catch (IOException e1) {
			e1.printStackTrace();
		} catch (Exception e) {
			e.printStackTrace();
		}
		return time;
	}

	/**
	 * TODO needs to be rewritten
	 */
	public HashMap<String, String> requestResult(long id)
			throws IllegalArgumentException {

		System.out.println("Server side @requestResult: getting results for id = " + id );

		HashMap<String, String> result = new HashMap<String, String>();
		
		if( id == 0 ){
			System.out.println("Server side @requestResult: Wrong id !" );			
			return result;
		}
		
		String db_ip="localhost";
		String db_name=getServletContext().getInitParameter("dbname");
		String db_user=getServletContext().getInitParameter("dbuser");
		String db_pass=getServletContext().getInitParameter("dbpass");
		
		CalculationDB db = new CalculationDB(db_ip, db_name, db_user, db_pass);
		String calcPath = db.getCalculation(id).getFilePath();
		
		Process p = processPool.get(id);
				
		try {
			if( p == null ){
				System.out.println("Server side @requestResult: Process not in the pool, problem !" );				
				result.put("stdout", "");
				result.put("stderr", "");
			} else {
				int exitcode = p.exitValue();
				String exitCodeString = String.valueOf(exitcode);	
				result.put("exitcode", exitCodeString);
				if( exitcode != 0){
					System.out.println("Server side @requestResult: Process finished, failure" );				
					result.put("stdout", CalculationFile.readContent(calcPath + ".stdout"));
					result.put("stderr", CalculationFile.readContent(calcPath + ".stderr"));
					processPool.remove(id);
					return result;	
				} 
			}
		} catch(java.lang.IllegalThreadStateException e1) {
			System.out.println("Server side @requestResult: Process not yet finished.");				
			result.put("stdout", "");
			result.put("stderr", "");
			return result;
		} catch (FileNotFoundException e1) {
			e1.printStackTrace();
		} catch (IOException e1) {
			e1.printStackTrace();
		}

		processPool.remove(id);
		
		try {
			System.out.println("Server side @requestResult: reading output from " + calcPath );
			result.putAll(CalculationFile.readData(calcPath));
			try{
				result.put("content", CalculationFile.readContent(calcPath));
			} catch (FileNotFoundException e) {
				Logger.error("Server side @requestResult: Results file " + calcPath + " not ready yet");
				result.put("content", "");
				e.printStackTrace();
			} catch (IOException e) {
				Logger.error("Server side @requestResult: Results file " + calcPath + " problem");
				result.put("content", "");
				e.printStackTrace();
			} 
			result.put("stdout", CalculationFile.readContent(calcPath + ".stdout"));
			result.put("stderr", CalculationFile.readContent(calcPath + ".stderr"));
		} catch (FileNotFoundException e) {
			Logger.error("Server side @requestResult: Results file " + calcPath + " not ready yet");
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		}

		return result;

	}

}
