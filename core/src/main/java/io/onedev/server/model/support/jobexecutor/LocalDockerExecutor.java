package io.onedev.server.model.support.jobexecutor;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.io.UnsupportedEncodingException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.annotation.Nullable;
import javax.validation.ConstraintValidatorContext;

import org.apache.commons.codec.Charsets;
import org.hibernate.validator.constraints.NotEmpty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Joiner;

import io.onedev.commons.utils.FileUtils;
import io.onedev.commons.utils.StringUtils;
import io.onedev.commons.utils.command.Commandline;
import io.onedev.commons.utils.command.LineConsumer;
import io.onedev.commons.utils.command.ProcessKiller;
import io.onedev.commons.utils.concurrent.ConstrainedRunner;
import io.onedev.server.model.support.jobexecutor.LocalDockerExecutor.TestData;
import io.onedev.server.util.OneContext;
import io.onedev.server.util.validation.Validatable;
import io.onedev.server.util.validation.annotation.ClassValidating;
import io.onedev.server.web.editable.annotation.Editable;
import io.onedev.server.web.editable.annotation.OmitName;
import io.onedev.server.web.editable.annotation.Password;
import io.onedev.server.web.editable.annotation.ShowCondition;
import io.onedev.server.web.util.Testable;

@Editable(order=100)
@ClassValidating
public class LocalDockerExecutor extends JobExecutor implements Testable<TestData>, Validatable {

	private static final long serialVersionUID = 1L;
	
	private static final Logger logger = LoggerFactory.getLogger(LocalDockerExecutor.class);

	private String dockerExecutable;
	
	private String dockerRegistry;
	
	private boolean authenticateToRegistry;
	
	private String userName;
	
	private String password;
	
	private String runOptions;
	
	private int capability = Runtime.getRuntime().availableProcessors();
	
	private transient ConstrainedRunner runner;

	@Editable(order=1000, description="Optionally specify docker executable, for instance <i>/usr/local/bin/docker</i>. "
			+ "Leave empty to use docker executable in PATH")
	public String getDockerExecutable() {
		return dockerExecutable;
	}

	public void setDockerExecutable(String dockerExecutable) {
		this.dockerExecutable = dockerExecutable;
	}

	@Editable(order=1100, description="Optionally specify a docker registry to use. Leave empty to use the official registry")
	public String getDockerRegistry() {
		return dockerRegistry;
	}

	public void setDockerRegistry(String dockerRegistry) {
		this.dockerRegistry = dockerRegistry;
	}

	@Editable(order=1150)
	public boolean isAuthenticateToRegistry() {
		return authenticateToRegistry;
	}

	public void setAuthenticateToRegistry(boolean authenticateToRegistry) {
		this.authenticateToRegistry = authenticateToRegistry;
	}

	@Editable(order=1200, description="Specify user name to access docker registry")
	@NotEmpty
	@ShowCondition("isRegistryAuthenticationRequired")
	public String getUserName() {
		return userName;
	}

	public void setUserName(String userName) {
		this.userName = userName;
	}

	@Editable(order=1300, description="Specify password to access docker registry")
	@Password
	@NotEmpty
	@ShowCondition("isRegistryAuthenticationRequired")
	public String getPassword() {
		return password;
	}

	public void setPassword(String password) {
		this.password = password;
	}
	
	@Editable(order=1400, description="Specify options to run container. For instance, you may use <tt>-m 2g</tt> "
			+ "to limit memory of created container to be 2 giga bytes")
	public String getRunOptions() {
		return runOptions;
	}

	public void setRunOptions(String runOptions) {
		this.runOptions = runOptions;
	}

	public static boolean isRegistryAuthenticationRequired() {
		return (boolean) OneContext.get().getEditContext().getInputValue("authenticateToRegistry");
	}

	@Editable(order=1400, description="Specify max number of concurrent jobs being executed. Each job execution "
			+ "will launch a separate docker container. Defaults to number of processors in the system")
	public int getCapability() {
		return capability;
	}

	public void setCapability(int capability) {
		this.capability = capability;
	}
	
	private Commandline getDockerCmd() {
		if (getDockerExecutable() != null)
			return new Commandline(getDockerExecutable());
		else
			return new Commandline("docker");
	}
	
	private String getPullImage(String image) {
		if (getDockerRegistry() != null) {
			if (image.contains("/"))
				return getDockerRegistry() + "/" + image;
			else
				return getDockerRegistry() + "/library/" + image;
		} else {
			return image;
		}
	}
	
	@SuppressWarnings("unchecked")
	private String getImageOS(Logger logger, String image) {
		logger.info("Checking image OS...");
		Commandline cmd = getDockerCmd();
		cmd.addArgs("inspect", image);
		
		StringBuilder output = new StringBuilder();
		cmd.execute(new LineConsumer(Charsets.UTF_8.name()) {

			@Override
			public void consume(String line) {
				logger.debug(line);
				output.append(line).append("\n");
			}
			
		}, newErrorLogger(logger)).checkReturnCode();

		Map<String, Object> map;
		try {
			map = (Map<String, Object>) new ObjectMapper()
					.readValue(output.toString(), List.class).iterator().next();
			return (String) map.get("Os");
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}
	
	private synchronized ConstrainedRunner getRunner() {
		if (runner == null)
			runner = new ConstrainedRunner(capability);
		return runner;
	}
	
	@Override
	public boolean hasCapacity() {
		return getRunner().hasCapacity();
	}

	@Override
	public void execute(String environment, List<String> commands, 
			@Nullable SourceSnapshot snapshot, Logger logger) {
		getRunner().run(new Runnable() {

			@Override
			public void run() {
				String jobInstance = UUID.randomUUID().toString();
				File workspace = createWorkspace();
				try {
					if (snapshot != null) {
						logger.info("Cloning source code...");
						snapshot.checkout(workspace);
					}
								
					login(logger);
					
					logger.info("Pulling image...") ;
					Commandline cmd = getDockerCmd();
					cmd.addArgs("pull", getPullImage(environment));
					cmd.execute(newInfoLogger(logger), newErrorLogger(logger)).checkReturnCode();
					
					cmd.clearArgs();
					cmd.addArgs("run", "--rm", "--name", jobInstance);
					if (getRunOptions() != null)
						cmd.addArgs(StringUtils.parseQuoteTokens(getRunOptions()));
					
					String imageOS = getImageOS(logger, environment);
					
					if (imageOS.equals("windows")) {
						logger.info("Image OS is windows, run commands with cmd.exe...");
						String environmentWorkspacePath = "C:\\" + ENVIRONMENT_WORKSPACE;
						File scriptFile = new File(workspace, "onedev-job-commands.bat");
						FileUtils.writeLines(scriptFile, commands, "\r\n");
						cmd.addArgs("-v", workspace.getAbsolutePath() + ":" + environmentWorkspacePath);
						cmd.addArgs("-w", environmentWorkspacePath);
						cmd.addArgs(environment);
						cmd.addArgs("cmd", "/c", environmentWorkspacePath + "\\onedev-job-commands.bat");
					} else {
						logger.info("Image OS is " + imageOS + ", run commands with sh...");
						String environmentWorkspacePath = "/" + ENVIRONMENT_WORKSPACE;
						File scriptFile = new File(workspace, "onedev-job-commands.sh");
						FileUtils.writeLines(scriptFile, commands, "\n");
						cmd.addArgs("-v", workspace.getAbsolutePath() + ":" + environmentWorkspacePath);
						cmd.addArgs("-w", environmentWorkspacePath);
						cmd.addArgs(environment);
						cmd.addArgs("sh", "-c", environmentWorkspacePath + "/onedev-job-commands.sh");
					}

					logger.info("Running container to execute job...");
					cmd.execute(newInfoLogger(logger), newErrorLogger(logger), null, new ProcessKiller() {

						@Override
						public void kill(Process process) {
							logger.info("Stopping container...");
							Commandline cmd = getDockerCmd();
							cmd.addArgs("stop", jobInstance);
							cmd.execute(newInfoLogger(logger), newErrorLogger(logger));
						}
						
					}).checkReturnCode();
				} catch (IOException e) {
					throw new RuntimeException(e);
				} finally {
					logger.info("Deleting workspace...");
					FileUtils.deleteDir(workspace);
				}
			}
			
		});
	}

	private LineConsumer newInfoLogger(Logger logger) {
		return new LineConsumer(Charsets.UTF_8.name()) {

			@Override
			public void consume(String line) {
				logger.info(line);
			}
			
		};
	}

	private LineConsumer newErrorLogger(Logger logger) {
		return new LineConsumer(Charsets.UTF_8.name()) {

			@Override
			public void consume(String line) {
				logger.error(line);
			}
			
		};
	}
	
	private void login(Logger logger) {
		if (isAuthenticateToRegistry()) {
			logger.info("Login to docker registry...");
			Commandline cmd = getDockerCmd();
			cmd.addArgs("login", "-u", getUserName(), "--password-stdin");
			if (getDockerRegistry() != null)
				cmd.addArgs(getDockerRegistry());
			ByteArrayInputStream input;
			try {
				input = new ByteArrayInputStream(getPassword().getBytes(Charsets.UTF_8.name()));
			} catch (UnsupportedEncodingException e) {
				throw new RuntimeException(e);
			}
			cmd.execute(newInfoLogger(logger), newErrorLogger(logger), input).checkReturnCode();
		}
	}
	
	private boolean hasOptions(String[] arguments, String... options) {
		for (String argument: arguments) {
			for (String option: options) {
				if (option.startsWith("--")) {
					if (argument.startsWith(option + "=") || argument.equals(option))
						return true;
				} else if (option.startsWith("-")) {
					if (argument.startsWith(option))
						return true;
				} else {
					throw new RuntimeException("Invalid option: " + option);
				}
			}
		}
		return false;
	}
	
	@Override
	public boolean isValid(ConstraintValidatorContext context) {
		if (getRunOptions() != null) {
			String[] arguments = StringUtils.parseQuoteTokens(getRunOptions());
			String invalidOptions[] = new String[] {"-w", "--workdir", "-d", "--detach", "-a", "--attach", "-t", "--tty", 
					"-i", "--interactive", "--rm", "--restart", "--name"}; 
			if (hasOptions(arguments, invalidOptions)) {
				context.disableDefaultConstraintViolation();
				StringBuilder errorMessage = new StringBuilder("Can not use options: "
						+ Joiner.on(", ").join(invalidOptions));
				context.buildConstraintViolationWithTemplate(errorMessage.toString())
						.addPropertyNode("runOptions").addConstraintViolation();
				return false;
			} 
		}
		return true;
	}
	
	@Override
	public void test(TestData testData) {
		logger.info("Testing local docker executor...");
		
		login(logger);
		
		logger.info("Pulling image...");
		
		Commandline cmd = getDockerCmd();
		cmd.addArgs("pull", getPullImage(testData.getDockerImage()));
		cmd.execute(newInfoLogger(logger), newErrorLogger(logger)).checkReturnCode();
		
		String imageOS = getImageOS(logger, testData.getDockerImage());
		
		logger.info("Running container...");
		File workspaceDir = FileUtils.createTempDir("workspace");
		try {
			cmd.clearArgs();
			cmd.addArgs("run", "--rm");
			if (getRunOptions() != null)
				cmd.addArgs(StringUtils.parseQuoteTokens(getRunOptions()));
			if (imageOS.equals("windows")) {
				String environmentWorkspacePath = "C:\\" + ENVIRONMENT_WORKSPACE;
				cmd.addArgs("-v", workspaceDir.getAbsolutePath() + ":" + environmentWorkspacePath);
				cmd.addArgs("-w", environmentWorkspacePath);
				cmd.addArgs(testData.getDockerImage());
				cmd.addArgs("cmd", "/c", "echo this is a test");
			} else {
				String environmentWorkspacePath = "/" + ENVIRONMENT_WORKSPACE;
				cmd.addArgs("-v", workspaceDir.getAbsolutePath() + ":" + environmentWorkspacePath);
				cmd.addArgs("-w", environmentWorkspacePath);
				cmd.addArgs(testData.getDockerImage());
				cmd.addArgs("sh", "-c", "echo this is a test");
			}
			cmd.execute(newInfoLogger(logger), newErrorLogger(logger)).checkReturnCode();
		} finally {
			FileUtils.deleteDir(workspaceDir);
		}
	}
	
	@Editable(name="Specify a Docker Image to Test Against")
	public static class TestData implements Serializable {

		private static final long serialVersionUID = 1L;

		private String dockerImage;

		@Editable
		@OmitName
		@NotEmpty
		public String getDockerImage() {
			return dockerImage;
		}

		public void setDockerImage(String dockerImage) {
			this.dockerImage = dockerImage;
		}
		
	}
	
}