package nl.arudos.jconfigfile;

import java.io.BufferedWriter;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;

import junit.framework.Test;
import junit.framework.TestCase;
import junit.framework.TestSuite;

/**
 * Unit test for simple App.
 */
public class FileTest 
    extends TestCase
{
    /**
     * Create the test case
     *
     * @param testName name of the test case
     */
    public FileTest( String testName )
    {
        super( testName );
    }

    /**
     * @return the suite of tests being tested
     */
    public static Test suite()
    {
        return new TestSuite( FileTest.class );
    }

    /**
     * Rigourous Test :-)
     */
    public void testApp()
    {
    	createTestFiles();
    }
    
	private void createTestFiles() {
		try {
			createTestFile("UTF-32BE");
		} catch (Exception e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		try {
			createTestFile("UTF-32LE");
		} catch (Exception e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		try {
			createTestFile("UTF-16BE");
		} catch (Exception e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

		try {
			createTestFile("UTF-16LE");
		} catch (Exception e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

		try {
			createTestFile("UnicodeBig");
		} catch (Exception e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

		try {
			createTestFile("UTF-16");
		} catch (Exception e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

		try {
			createTestFile("UTF-32");
		} catch (Exception e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

	}

	private void createTestFile(String charset) throws Exception {
		FileOutputStream os = null;
		BufferedWriter bw = null;
		try {
			os = new FileOutputStream("D:\\Development\\" + charset + ".txt");
			bw = new BufferedWriter(new OutputStreamWriter(os, charset));
			// bw.write("Test 1\r\nTest 2\r\nTest 3\r\n");
			bw.write("Test 1\r\n");
		} finally {
			if (bw != null) {
				try {
					bw.close();
				} catch (Exception e) {
				}
			}
			if (os != null) {
				try {
					os.close();
				} catch (Exception e) {
				}
			}
		}

	}
    
    
    
}
