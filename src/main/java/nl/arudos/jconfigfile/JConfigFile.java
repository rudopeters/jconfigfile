package nl.arudos.jconfigfile;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
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

public class JConfigFile {
	private final char BOM_CHAR = 0xFEFF;
	private boolean loaded = false;
	private List<ConfigLine> configLines = null;
	private Map<ConfigLine, ArrayList<ConfigLine>> sectionMap = null;
	private File file;
	private String detectedSeparator = "";
	private boolean detectedBOM = false;
	private Charset detectedCharset = null;
	private Charset suppliedCharset = null;

	public JConfigFile(File file) {
		init(file, null);
	}

	public JConfigFile(File file, String encoding) {
		init(file, encoding);
	}

	final private void init(File file, String encoding) {
		this.file = file;
		this.suppliedCharset = (encoding != null) ? Charset.forName(encoding) : null;
	}

	private void loadWhenRequired() throws IOException {
		if (!loaded) {
			load();
		}
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
	
	private void setDetectedSeparator(String separator) {
		this.detectedSeparator = separator;
	}

	public String getDetectedSeparator() {
		return this.detectedSeparator;
	}

	private void setDetectedBOM(boolean b) {
		this.detectedBOM = b;
	}

	public boolean getDetectedBOM() {
		return this.detectedBOM;
	}

	private void setDetectedCharset(Charset cs) {
		this.detectedCharset = cs;
	}

	public Charset getDetectedCharset() {
		return this.detectedCharset;
	}

	public Charset getSuppliedCharset() {
		return this.suppliedCharset;
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
		Charset cs = this.getSuppliedCharset();

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
		this.setDetectedCharset(cs);

	}

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
				new InputStreamReader(new FileInputStream(file), this.getDetectedCharset()))) {
			int i;
			int j = 0;
			data = "";
			while ((i = br.read()) != -1) {
				if (j == 0) {
					this.setDetectedBOM(i == BOM_CHAR);
					j++;
					continue;
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

			this.setDetectedSeparator(separator);

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
		setSystemProperties();
		replaceProperties();
		
		this.loaded = true;
		
	}

	public void save() throws IOException {
		try (BufferedWriter bw = new BufferedWriter(
				new OutputStreamWriter(new FileOutputStream(file), this.getDetectedCharset()))) {
			if (this.getDetectedBOM()) {
				bw.write(BOM_CHAR);
			}
			for (ConfigLine configLine : configLines) {
				if (!configLine.getLine().equals("[]")) {
					bw.write(configLine.getLine() + this.getDetectedSeparator());
				}
			}
		}
		this.loaded = false;
	}

	private void parse() throws FileNotFoundException, IOException {
		ArrayList<ConfigLine> currentSectionLines = null;
		ConfigLine currentSection = null;
		Pattern pattern = Pattern.compile("^\\s?\\[(.*)\\]\\s?$");
		for (ConfigLine configLine : this.configLines) {
			if (!configLine.hasData()) {
				continue;
			}
			String line = configLine.getData();
			Matcher m = pattern.matcher(line);
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
						throw new IllegalArgumentException(String.format("Duplicate section '%s'", configLine.getSection()));
					}
				}
				currentSectionLines = new ArrayList<ConfigLine>();
				currentSection = configLine;
			} else {
				for (ConfigLine l : currentSectionLines) {
					if (l.getKey().equalsIgnoreCase(configLine.getKey())) {
						throw new IllegalArgumentException(String.format("Duplicate key '%s' in section '%s'", configLine.getKey(), currentSection.getSection()));
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

	private void replaceProperties() throws IOException {
		Pattern variablePattern = Pattern.compile(".*\\$\\{(.+?)\\}.*");
		Pattern sectionAndPropertyPattern = Pattern.compile("^\\[(.*)\\]\\.(.*?)$");
		int count;
		do {
			count = 0;
			for (Map.Entry<ConfigLine, ArrayList<ConfigLine>> entry : sectionMap.entrySet()) {
				ArrayList<ConfigLine> configLines = entry.getValue();
				for (int i = 0; i < configLines.size(); i++) {
					String s = configLines.get(i).getData();
					Matcher m = variablePattern.matcher(s);
					if (m.matches() && m.groupCount() >= 1) {
						for (int j = 1; j <= m.groupCount(); j++) {
							String propertyName = m.group(j);
							if (propertyName.isEmpty()) {
								continue;
							}

							String newValue = null;

							if (propertyName.startsWith("[")) {
								Matcher matcher = sectionAndPropertyPattern.matcher(propertyName);
								if (matcher.matches() && matcher.groupCount() == 3) {
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

							if (newValue != null) {
								s = s.replace("${" + propertyName + "}", newValue);
								configLines.get(i).setData(s);
								count++;
							}

						}
					}
				}
			}
		} while (count > 0);
	}
	
	private void setSystemProperties() {
		for (Map.Entry<ConfigLine, ArrayList<ConfigLine>> entry : sectionMap.entrySet()) {
			if (entry.getKey().getSection().equalsIgnoreCase("system properties")) {
				ArrayList<ConfigLine> configLines = entry.getValue();
				for (ConfigLine configLine : configLines) {
					if (!configLine.hasData()) {
						continue;
					}
					String[] parts = configLine.getData().split("=", 2);
					if (parts.length != 2 || parts[0].trim().isEmpty()) {
						throw new IllegalArgumentException(
								String.format("System property '%s' is invalid", configLine.getData()));
					}
					System.setProperty(parts[0].trim(), parts[1]);
				}
				break;
			}
		}
	}

	public List<String> getSections() throws IOException {
		this.loadWhenRequired();
		List<String> sections = new ArrayList<String>();
		for (ConfigLine configLine : sectionMap.keySet()) {
			sections.add(configLine.getSection());
		}
		return sections;
	}
	
	public List<String> getKeys(String sectionName) throws IOException {
		this.loadWhenRequired();
		List<String> keys = new ArrayList<>();
		String section = validateNotNull("Section", sectionName).trim();
		for (Map.Entry<ConfigLine, ArrayList<ConfigLine>> entry : sectionMap.entrySet()) {
			if (entry.getKey().getSection().equalsIgnoreCase(section)) {
				for (ConfigLine configLine : entry.getValue()) {
					keys.add(configLine.getKey());
				}
				break;
			}
		}
		return keys;
	}

	public boolean hasKey(String sectionName, String keyName) throws IOException {
		this.loadWhenRequired();
		String section = validateNotNull("Section", sectionName).trim();
		String key = validateNotNullOrEmpty("Key", keyName).trim();
		for (Map.Entry<ConfigLine, ArrayList<ConfigLine>> entry : sectionMap.entrySet()) {
			if (entry.getKey().getSection().equalsIgnoreCase(section)) {
				for (ConfigLine configLine : entry.getValue()) {
					if (configLine.getKey().equalsIgnoreCase(key)) {
						return true;
					}
				}
			}
		}
		return false;
	}
	
	public String getValue(String sectionName, String keyName) throws IOException {
		this.loadWhenRequired();
		String section = validateNotNull("Section", sectionName).trim();
		String key = validateNotNullOrEmpty("Key", keyName).trim();
		for (Map.Entry<ConfigLine, ArrayList<ConfigLine>> entry : sectionMap.entrySet()) {
			if (entry.getKey().getSection().equalsIgnoreCase(section)) {
				for (ConfigLine configLine : entry.getValue()) {
					if (configLine.getKey().equalsIgnoreCase(key)) {
						return configLine.getValue();
					}
				}
			}
		}
		return null;
	}

	public boolean addSection(String sectionName) throws IOException {
		this.loadWhenRequired();
		String section = validateNotNull("Section", sectionName).trim();
		for (Map.Entry<ConfigLine, ArrayList<ConfigLine>> entry : sectionMap.entrySet()) {
			if (entry.getKey().getSection().equalsIgnoreCase(section)) {
				return false;
			}
		}
		validateNotNullOrEmpty("Section", sectionName);
		configLines.add(new ConfigLine("[" + section + "]"));
		save();
		return true;
	}
	
	public boolean setItem(String sectionName, String itemKey, String itemValue) throws IOException {
		this.loadWhenRequired();
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
					configLines.add(j+1, newLine);
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
