package nl.arudos.jconfigfile;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import javax.script.ScriptException;

/**
 * Class for reading and writing textual configuration files The configuration
 * files can be ini-style files, files without sections, or a mixture of both
 *
 * Supported value replacements: other variable:
 *   driver=${[Browser settings].webdriver.binary}
 *   environment variable: hostname=${COMPUTERNAME}
 *   system property: vendor=${java.vendor}
 *   JavaScript expression: result=${!-var s='hello'; s;-!}
 * 
 * @author Rudo Peters
 *
 */
public class JConfigFile {
	private final char BOM_CHAR = 0xFEFF;
	private List<ConfigLine> configLines = null;
	private Map<ConfigLine, ArrayList<ConfigLine>> sectionMap = null;
	private File file;
	private String detectedSeparator = "";
	private boolean detectedBOM = false;
	private Charset detectedCharset = null;
	private Charset suppliedCharset = null;
	public final String ENCODING_ANSI = "Cp1252";

	/**
	 * Initialise the configuration file This will trigger a file load
	 * 
	 * @param file
	 *            The configuration File
	 * @throws IOException
	 */
	public JConfigFile(File file) throws IOException {
		this.file = file;
		load();
	}

	/**
	 * Initialise the configuration file with the supplied character encoding
	 * This will trigger a file load
	 * 
	 * @param file
	 *            The configuration File
	 * @param encoding
	 *            The file encoding, for example Cp1252 (a.k.a ANSI), UTF-8,
	 *            etc, or null to attempt auto detection of the encoding
	 * @throws IOException
	 */
	public JConfigFile(File file, String encoding) throws IOException {
		this.file = file;
		this.suppliedCharset = (encoding != null) ? Charset.forName(encoding) : null;
		load();
	}

	private String validateNotNull(String field, String value) {
		if (value == null) {
			throw new IllegalArgumentException(String.format("%s must not be null", field));
		}
		return value;
	}

	private String validateNotNullOrEmpty(String field, String value) {
		if (validateNotNull(field, value).trim().isEmpty()) {
			throw new IllegalArgumentException(String.format("%s must not be empty", field));
		}
		return value;
	}

	/**
	 * Get the line separator
	 * 
	 * @return String with the line separator
	 */
	public String getLineSeparator() {
		return this.detectedSeparator;
	}

	/**
	 * Check if the file has a BOM (byte order mark)
	 * 
	 * @return boolean true if a BOM was found, false otherwise
	 */
	public boolean hasBOM() {
		return this.detectedBOM;
	}

	/**
	 * Get the Charset
	 * 
	 * @return Charset
	 */
	public Charset getCharset() {
		return this.detectedCharset;
	}

	private void detectCharset() throws IOException {
		final String[][] knownBOMs = new String[][] {
				new String[] { "UTF-8", new String(new char[] { 0xEF, 0xBB, 0xBF }) },
				new String[] { "UnicodeBig", new String(new char[] { 0xFE, 0xFF }) },
				new String[] { "UnicodeLittle", new String(new char[] { 0xFF, 0xFE }) },
				new String[] { "x-UTF-32BE-BOM", new String(new char[] { 0x00, 0x00, 0xFE, 0xFF }) },
				new String[] { "x-UTF-32LE-BOM", new String(new char[] { 0xFF, 0xFE, 0x00, 0x00 }) } };

		String potentialBOM = "";
		String data = "";

		// read the BOM and the first 4 data bytes from the file
		try (FileInputStream is = new FileInputStream(file)) {
			int i;
			int j = 0;
			while ((i = is.read()) != -1 && j < 8) {
				data += (char) i;
				if (j < 4) {
					potentialBOM += (char) i;
				}
				j++;
			}
		}

		// use the supplied Charset (if any) as the default
		Charset cs = this.suppliedCharset;

		// if the file has a BOM, use that for determining the Charset
		for (int k = 0; k < knownBOMs.length; k++) {
			if (potentialBOM.startsWith(knownBOMs[k][1])) {
				cs = Charset.forName(knownBOMs[k][0]);
				break;
			}
		}

		// if the Charset is still unknown, try to find the number of bytes per
		// character and set the Charset accordingly
		if (cs == null && data.length() >= 2) {
			int byte0 = data.charAt(0);
			int byte1 = data.charAt(1);
			if (data.length() >= 4) {
				int byte2 = data.charAt(2);
				int byte3 = data.charAt(3);
				if (byte0 == 0 && byte1 == 0 && byte2 == 0 && byte3 != 0) {
					cs = Charset.forName("UTF-32BE");
				} else if (byte0 != 0 && byte1 == 0 && byte2 == 0 && byte3 == 0) {
					cs = Charset.forName("UTF-32LE");
				}
			}
			if (cs == null) {
				if (byte0 == 0 && byte1 != 0) {
					cs = Charset.forName("UTF-16BE");
				} else if (byte0 != 0 && byte1 == 0) {
					cs = Charset.forName("UTF-16LE");
				}
			}
		}

		// otherwise... assume we are dealing with a UTF-8 file
		if (cs == null) {
			cs = Charset.forName("UTF-8");
		}

		// save the detected values
		this.detectedCharset = cs;

	}

	/**
	 * Load the configuration file
	 * 
	 * @throws IOException
	 */
	public void load() throws IOException {
		final char CR = new String("\r").charAt(0);
		final char LF = new String("\n").charAt(0);

		sectionMap = null;
		sectionMap = new LinkedHashMap<ConfigLine, ArrayList<ConfigLine>>();
		configLines = null;
		configLines = new ArrayList<ConfigLine>();

		detectCharset();

		String data = "";
		String separator = "";

		try (BufferedReader br = new BufferedReader(
				new InputStreamReader(new FileInputStream(file), this.getCharset()))) {
			int i;
			int j = 0;
			data = "";
			while ((i = br.read()) != -1) {
				if (j == 0) {
					j++;
					if (i == BOM_CHAR) {
						this.detectedBOM = true;
						continue;
					}
					this.detectedBOM = false;
				}
				if ((char) i == CR) {
					separator += CR;
				} else if ((char) i == LF) {
					separator += LF;
				} else if (separator.length() > 0) {
					break;
				} else {
					data += (char) i;
				}
			}

			this.detectedSeparator = separator;

			// add the default section
			configLines.add(new ConfigLine("[]"));

			configLines.add(new ConfigLine(data));

			if (i == -1) {
				return;
			}

			// read the remainder of the second line
			data = br.readLine();
			if (data != null) {
				configLines.add(new ConfigLine((char) i + data));
			} else {
				configLines.add(new ConfigLine((char) i + ""));
				return;
			}

			// read the other lines
			while ((data = br.readLine()) != null) {
				configLines.add(new ConfigLine(data));
			}

		}

		parse();
		replaceProperties();
	}

	/**
	 * Save the configuration file
	 * 
	 * @throws IOException
	 */
	public void save() throws IOException {
		try (BufferedWriter bw = new BufferedWriter(
				new OutputStreamWriter(new FileOutputStream(file), this.getCharset()))) {
			if (this.hasBOM()) {
				bw.write(BOM_CHAR);
			}
			for (ConfigLine configLine : configLines) {
				if (!configLine.getLine().equals("[]")) {
					bw.write(configLine.getLine() + this.getLineSeparator());
				}
			}
		}
	}

	private void parse() {
		ArrayList<ConfigLine> currentSectionLines = null;
		ConfigLine currentSection = null;
		Pattern sectionPattern = Pattern.compile("^\\s?\\[(.*)\\]\\s?$");
		for (ConfigLine configLine : this.configLines) {
			if (!configLine.hasData()) {
				continue;
			}
			String line = configLine.getData();
			Matcher m = sectionPattern.matcher(line);
			if (m.matches() && m.groupCount() == 1) {
				if (currentSectionLines != null) {
					// store the previous section
					sectionMap.put(currentSection, currentSectionLines);
					currentSectionLines = null;
				}
				// create the new section
				configLine.setSection(m.group(1).trim());
				for (ConfigLine l : sectionMap.keySet()) {
					if (l.getSection().equalsIgnoreCase(configLine.getSection())) {
						throw new IllegalArgumentException(
								String.format("Duplicate section '%s'", configLine.getSection()));
					}
				}
				currentSectionLines = new ArrayList<ConfigLine>();
				currentSection = configLine;
			} else {
				for (ConfigLine l : currentSectionLines) {
					if (l.getKey().equalsIgnoreCase(configLine.getKey())) {
						throw new IllegalArgumentException(String.format("Duplicate key '%s' in section '%s'",
								configLine.getKey(), currentSection.getSection()));
					}
				}
				currentSectionLines.add(configLine);
			}
		}
		if (currentSectionLines != null) {
			// store the last section
			sectionMap.put(currentSection, currentSectionLines);
		}

	}

	private String executeJavascript(String script) {
		ScriptEngineManager factory = new ScriptEngineManager();
		ScriptEngine engine = factory.getEngineByName("JavaScript");
		try {
			return (String) engine.eval(script).toString();
		} catch (ScriptException e) {
			return "##EXCEPTION: " + e.getLocalizedMessage();
		}
	}

	private void replaceProperties() {
		Pattern variablePattern = Pattern.compile(".*(\\$\\{(!-)?(.+?)(-!)?\\}).*");
		Pattern sectionPattern = Pattern.compile("^\\[(.*)\\]:(.*?)$");
		int count;
		do {
			count = 0;
			for (Map.Entry<ConfigLine, ArrayList<ConfigLine>> entry : sectionMap.entrySet()) {
				ArrayList<ConfigLine> configLines = entry.getValue();
				for (int i = 0; i < configLines.size(); i++) {
					String s = configLines.get(i).getData();
					Matcher m = variablePattern.matcher(s);
					if (m.matches()) {
						String newValue = null;
						if (m.group(2) != null || m.group(4) != null) {
							if (m.group(2) == null) {
								throw new RuntimeException("JavaScript start tag '!-' not found");
							} else if (m.group(4) == null) {
								throw new RuntimeException("JavaScript end tag '-!' not found");
							}
							newValue = executeJavascript(m.group(3));
						} else {
							String propertyName = m.group(3);
							if (propertyName.isEmpty()) {
								continue;
							}
							if (propertyName.startsWith("[")) {
								Matcher matcher = sectionPattern.matcher(propertyName);
								if (matcher.matches() && matcher.groupCount() == 2) {
									String section = matcher.group(1);
									String name = matcher.group(2);
									newValue = getValue(section, name);
								}
							}
							if (newValue == null) {
								newValue = System.getenv(propertyName);
							}
							if (newValue == null) {
								newValue = System.getProperty(propertyName);
							}
						}
						if (newValue != null) {
							s = s.replace(m.group(1), newValue);
							configLines.get(i).setData(s);
							count++;
						}
					}
				}
			}
		} while (count > 0);
	}

	/**
	 * Get a list of all sections
	 * 
	 * @return List of sections
	 */
	public List<String> getSections() {
		List<String> sections = new ArrayList<String>();
		for (ConfigLine configLine : sectionMap.keySet()) {
			sections.add(new String(configLine.getSection()));
		}
		return sections;
	}

	/**
 	 * Check if a section exists
 	 * 
	 * @param sectionName
	 *            Name of the section (without square brackets) 
	 * @return boolean true if the section exists, false otherwise
	 */
	public boolean hasSection(String sectionName) {
		String section = validateNotNull("Section", sectionName).trim();
		for (ConfigLine configLine : sectionMap.keySet()) {
			if (configLine.getSection().equalsIgnoreCase(section)) {
				return true;
			}
		}
		return false;
	}
	
	/**
	 * Get a list of all keys in a section
	 * 
	 * @param sectionName
	 *            Name of the section (without square brackets)
	 * @return List of all keys in the section
	 */
	public List<String> getKeys(String sectionName) {
		List<String> keys = new ArrayList<>();
		String section = validateNotNull("Section", sectionName).trim();
		for (Map.Entry<ConfigLine, ArrayList<ConfigLine>> entry : sectionMap.entrySet()) {
			if (entry.getKey().getSection().equalsIgnoreCase(section)) {
				for (ConfigLine configLine : entry.getValue()) {
					keys.add(new String(configLine.getKey()));
				}
				break;
			}
		}
		return keys;
	}

	/**
	 * Check if a certain key exists in a section
	 * 
	 * @param sectionName
	 *            Name of the section (without square brackets)
	 * @param keyName
	 *            Name of the key
	 * @return boolean true if the key exists, false if it does not exist
	 */
	public boolean hasKey(String sectionName, String keyName) {
		String section = validateNotNull("Section", sectionName).trim();
		String key = validateNotNullOrEmpty("Key", keyName).trim();
		for (Map.Entry<ConfigLine, ArrayList<ConfigLine>> entry : sectionMap.entrySet()) {
			if (entry.getKey().getSection().equalsIgnoreCase(section)) {
				for (ConfigLine configLine : entry.getValue()) {
					if (configLine.getKey().equalsIgnoreCase(key)) {
						return true;
					}
				}
				return false;
			}
		}
		return false;
	}

	/**
	 * Get the value for a key
	 * 
	 * @param sectionName
	 *            Name of the section (without square brackets)
	 * 
	 * @param keyName
	 *            Name of the key
	 * @return String value of the key or null if the key is not present or if
	 *         the value is empty
	 */
	public String getValue(String sectionName, String keyName) {
		String section = validateNotNull("Section", sectionName).trim();
		String key = validateNotNullOrEmpty("Key", keyName).trim();
		for (Map.Entry<ConfigLine, ArrayList<ConfigLine>> entry : sectionMap.entrySet()) {
			if (entry.getKey().getSection().equalsIgnoreCase(section)) {
				for (ConfigLine configLine : entry.getValue()) {
					if (configLine.getKey().equalsIgnoreCase(key)) {
						return new String(configLine.getValue());
					}
				}
			}
		}
		return null;
	}

	/**
	 * Add a new section to the end of the configuration file This will trigger
	 * a file write and reload
	 * 
	 * @param sectionName
	 *            Name of the new section (without square brackets)
	 * @return true when the section was added or false if it already existed
	 * @throws IOException
	 */
	public boolean addSection(String sectionName) throws IOException {
		String section = validateNotNull("Section", sectionName).trim();
		if (section.isEmpty()) {
			return false;
		}
		for (Map.Entry<ConfigLine, ArrayList<ConfigLine>> entry : sectionMap.entrySet()) {
			if (entry.getKey().getSection().equalsIgnoreCase(section)) {
				return false;
			}
		}
		configLines.add(new ConfigLine("[" + section + "]"));
		save();
		return true;
	}

	/**
	 * Set the value for a key This will trigger a file write and reload
	 * 
	 * @param sectionName
	 *            Name of the section
	 * @param itemKey
	 *            Name of the key
	 * @param itemValue
	 *            Value of the key, can be null to set a key without value or
	 *            empty to set an empty value
	 * @return boolean true if the value was set or false if it was not set
	 *         because the section does not exist
	 * @throws IOException
	 */
	public boolean setItem(String sectionName, String itemKey, String itemValue) throws IOException {
		addSection(sectionName);
		String section = sectionName.trim();
		String key = validateNotNullOrEmpty("Key", itemKey).trim();
		String data;
		for (Map.Entry<ConfigLine, ArrayList<ConfigLine>> entry : sectionMap.entrySet()) {
			if (entry.getKey().getSection().equalsIgnoreCase(section)) {
				ConfigLine lastLine = null;
				for (ConfigLine configLine : entry.getValue()) {
					String[] parts = configLine.getData().split("=", 2);
					if (parts[0].trim().equalsIgnoreCase(key)) {
						if (itemValue != null) {
							data = parts[0] + "=" + itemValue;
						} else {
							data = parts[0];
						}
						configLine.setData(data);
						save();
						return true;
					}
					lastLine = configLine;
				}

				if (itemValue != null) {
					data = key + "=" + itemValue;
				} else {
					data = key;
				}

				ConfigLine newLine = new ConfigLine(data);

				int j = configLines.lastIndexOf(lastLine);
				if (j == -1) {
					j = configLines.lastIndexOf(entry.getKey());
				}

				if (j < configLines.size()) {
					configLines.add(j + 1, newLine);
				} else {
					configLines.add(newLine);
				}

				entry.getValue().add(newLine);

				save();

				return true;
			}
		}
		return false;
	}

}

class ConfigLine {
	private String data = null;
	private String comment = null;
	private String key = null;
	private String value = null;
	private String section = null;

	public ConfigLine(String line) {
		Pattern commentPattern = Pattern.compile(".*?([;#!]+).*?");
		Matcher matcher = commentPattern.matcher(line);
		while (matcher.find()) {
			if (matcher.start(1) == 0) {
				setData(null);
				setComment(line);
				return;
			}

			String part1 = line.substring(0, matcher.start(1));
			String part2 = line.substring(matcher.start(1));

			if (part1.trim().isEmpty()) {
				// line starts with a comment
				setData(null);
				setComment(line);
				return;
			} else if (part1.endsWith(" ")) {
				// line contains a comment preceded by whitespace
				setData(part1.substring(0, part1.length() - 1));
				setComment(" " + part2);
				return;
			}

		}

		setData(line);

	}

	public void setSection(String section) {
		this.section = section;
	}

	public String getSection() {
		return section;
	}

	protected void setData(String data) {
		this.data = data;
		parseData();
	}

	public String getData() {
		return this.data;
	}

	public boolean hasData() {
		return (this.data != null);
	}

	public void setComment(String comment) {
		this.comment = comment;
	}

	public String getComment() {
		return this.comment;
	}

	public boolean hasComment() {
		return (this.comment != null);
	}

	private void parseData() {
		if (data == null) {
			key = null;
			value = null;
			return;
		}

		String[] parts = data.split("=", 2);
		if (parts.length == 1) {
			key = data.trim();
			value = null;
			return;
		}

		key = parts[0].trim();
		value = parts[1];

	}

	public String getKey() {
		return key;
	}

	public String getValue() {
		return value;
	}

	public String getLine() {
		return (hasData() ? getData() : "") + (hasComment() ? getComment() : "");
	}

}
