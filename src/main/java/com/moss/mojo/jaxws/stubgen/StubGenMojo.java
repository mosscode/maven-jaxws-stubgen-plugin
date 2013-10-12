/**
 * Copyright (C) 2013, Moss Computing Inc.
 *
 * This file is part of maven-jaxws-stubgen-plugin.
 *
 * maven-jaxws-stubgen-plugin is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2, or (at your option)
 * any later version.
 *
 * maven-jaxws-stubgen-plugin is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with maven-jaxws-stubgen-plugin; see the file COPYING.  If not, write to the
 * Free Software Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA
 * 02110-1301 USA.
 *
 * Linking this library statically or dynamically with other modules is
 * making a combined work based on this library.  Thus, the terms and
 * conditions of the GNU General Public License cover the whole
 * combination.
 *
 * As a special exception, the copyright holders of this library give you
 * permission to link this library with independent modules to produce an
 * executable, regardless of the license terms of these independent
 * modules, and to copy and distribute the resulting executable under
 * terms of your choice, provided that you also meet, for each linked
 * independent module, the terms and conditions of the license of that
 * module.  An independent module is a module which is not derived from
 * or based on this library.  If you modify this library, you may extend
 * this exception to your version of the library, but you are not
 * obligated to do so.  If you do not wish to do so, delete this
 * exception statement from your version.
 */
package com.moss.mojo.jaxws.stubgen;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.factory.ArtifactFactory;
import org.apache.maven.artifact.metadata.ArtifactMetadataSource;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.artifact.resolver.ArtifactResolutionResult;
import org.apache.maven.artifact.resolver.ArtifactResolver;
import org.apache.maven.model.Plugin;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3Dom;

import com.moss.jaxws.stubgen.StubGen;

/**
 * @phase process-classes
 * @goal generate
 */
public class StubGenMojo extends AbstractMojo {
	
	 /** @component */
    private ArtifactFactory artifactFactory;

    /** @component */
    private ArtifactResolver resolver;

    /** @component */
    private ArtifactMetadataSource artifactMetadataSource;

    /** @parameter expression="${project}" */
    private MavenProject project;

    /**@parameter expression="${localRepository}" */
    private ArtifactRepository localRepository;

    /** @parameter expression="${project.runtimeClasspathElements}" */
    private List<String> classpathElements;

    /** @parameter expression="${project.remoteArtifactRepositories}" */
    private List<ArtifactRepository> remoteRepositories;

    /** @parameter */
    private List<String> interfaces;
	
	public void execute() throws MojoExecutionException, MojoFailureException {
		
		/*
		 * load up all compiled classes to this point + their dependencies
		 */
		
		URLClassLoader cl = buildProjectClassloader();
		
		/*
		 * generate the specified interface wrapper classes
		 */
		
		File jaxwsDir = new File(project.getBasedir(), "src/main/java/");
		
		if (!jaxwsDir.exists() && !jaxwsDir.mkdirs()) {
			throw new MojoExecutionException("Could not create output directory: " + jaxwsDir.getAbsolutePath());
		}
		
		final List<File> filesToCompile = new ArrayList<File>();
		
		for (String iface : interfaces) {
			
			Class<?> clazz;
			try {
				clazz = cl.loadClass(iface);
			}
			catch (ClassNotFoundException ex) {
				throw new MojoExecutionException("Class not found: " + iface);
			}
			
			String pkg = clazz.getPackage().getName().replaceAll("\\.", "/");
			File targetPath = new File(jaxwsDir, pkg + "/jaxws");
			
			if (getLog().isInfoEnabled()) {
				getLog().info("Generating wrapper classes for SEI " + clazz.getName() + " in " + targetPath);
			}
			
			for (File file : StubGen.generate(clazz, targetPath)) {
				filesToCompile.add(file);
			}
		}
		
		/*
		 * compile the generated sources
		 */
		
		String classesDir = new File(project.getBuild().getDirectory(), "classes").getAbsolutePath();
		
		String source = null;
		String target = null;
		boolean debug = false;
		{

			Xpp3Dom compilerConfig = null;

			for (Object o : project.getBuildPlugins()) {
				Plugin plugin = (Plugin)o;
				
				if (plugin.getArtifactId().equals("maven-compiler-plugin")) {
					compilerConfig = (Xpp3Dom) plugin.getConfiguration();
					break;
				}
			}
			
			if (compilerConfig != null) {
				
				Xpp3Dom sourceNode = compilerConfig.getChild("source");
				if (sourceNode != null) {
					source = sourceNode.getValue();
				}
				
				Xpp3Dom targetNode = compilerConfig.getChild("target");
				if (targetNode != null) {
					target = targetNode.getValue();
				}
				
				Xpp3Dom debugNode = compilerConfig.getChild("debug");
				if (debugNode != null) {
					try {
						debug = Boolean.parseBoolean(debugNode.getValue());
					}
					catch (Exception ex) {
						if (getLog().isWarnEnabled()) {
							getLog().warn("Could not determine the value of 'debug' while re-using the compiler plugin configuration.");
						}
					}
				}
			}
		}
		
		List<String> args = new ArrayList<String>();
		
		if (source != null) {
			args.add("-source");
			args.add(source);
		}
		
		if (target != null) {
			args.add("-target");
			args.add(target);
		}
		
		if (debug) {
			args.add("-g");
		}
		
		args.add("-cp");
		
		String classpath;
		{
			StringBuilder cp = new StringBuilder();
			int count = 0;
			for (URL url : cl.getURLs()) {

				cp.append(url.getFile());

				if (count + 1 < cl.getURLs().length) {
					cp.append(":");
				}

				count++;
			}
			
			classpath = cp.toString();
		}
		args.add(classpath);
		
		args.add("-d"); 
		args.add(classesDir);
		
		for (File f : filesToCompile) {
			args.add(f.getAbsolutePath());
		}

		if (getLog().isInfoEnabled()) {
			getLog().info("Compiling " + filesToCompile.size() + " source files to " + classesDir);
		}
		
		if (getLog().isDebugEnabled()) {
			
			StringBuilder cmd = new StringBuilder();
			
			int i = 0;
			for (String arg : args) {
				
				cmd.append(arg);
				
				if (i + 1 < args.size()) {
					cmd.append(" ");
				}
				
				i++;
			}
			
			getLog().info("Executing: " + cmd);
		}

		try {
			
			ProcessExecutionResults results = execute("javac", args.toArray(new String[0]));
			
			if (results.status != 0) {
				
				getLog().error("Execution of command 'javac " + print(args) + "' failed with process return status " + results.status + ", stderr:\n" + new String(results.stdErr) + ", stdout: " + new String(results.stdOut));
				throw new MojoExecutionException("Compilation of generated jaxws sources failed.");
			}
			else if (getLog().isWarnEnabled()) {
				
				String stderr = new String(results.stdErr);
				
				if (stderr.trim().length() > 0) {
					getLog().warn("Execution of command 'javac " + print(args) + "' resulted in the following stderr output:\n" + stderr);
				}
			}
		}
		catch (IOException ex) {
			throw new MojoExecutionException("Compilation of generated jaxws sources failed.", ex);
		}
	}
	
	private String print(List<String> args) {

		if (getLog().isDebugEnabled()) {

			StringBuilder cmd = new StringBuilder();

			int i = 0;
			for (String arg : args) {

				cmd.append(arg);

				if (i + 1 < args.size()) {
					cmd.append(" ");
				}

				i++;
			}

			return cmd.toString();
		}
		else {
			return "...";
		}
	}
	
	@SuppressWarnings({ "unchecked", "deprecation" })
	private URLClassLoader buildProjectClassloader() {
		try {

			Set<URL> classpathUrls = new HashSet<URL>();

			Set<Artifact> artifacts = project.createArtifacts(artifactFactory, null, null);
			ArtifactResolutionResult arr = resolver.resolveTransitively(artifacts, project.getArtifact(), localRepository, remoteRepositories, artifactMetadataSource, null);

			for (Artifact resolvedArtifact : (Set<Artifact>)arr.getArtifacts()) {
				classpathUrls.add(resolvedArtifact.getFile().toURL());
			}

			for (String e : classpathElements) {
				try {
					classpathUrls.add(new File(e).toURL());
				}
				catch (MalformedURLException ex) {
					throw new RuntimeException(ex);
				}
			}

			URLClassLoader mojoCl = (URLClassLoader)this.getClass().getClassLoader();

			Set<URL> duplicateUrls = new HashSet<URL>();
			for (URL alreadyLoadedUrl : mojoCl.getURLs()) {
				for (URL url : classpathUrls) {
					if (url.equals(alreadyLoadedUrl)) {
						duplicateUrls.add(url);
					}
				}
			}
			for (URL duplicateUrl : duplicateUrls) {
				classpathUrls.remove(duplicateUrl);
				getLog().debug("removing duplicate url from project-classpath: " + duplicateUrl);
			}

			URLClassLoader cl = new URLClassLoader(classpathUrls.toArray(new URL[0]), null);

			getLog().debug("dumping out project-classpath:");
			for (URL url : cl.getURLs()) {
				getLog().debug(url.toString());
			}

			return cl;
		}
		catch (Exception ex) {
			throw new RuntimeException(ex);
		}
	}
	
	public ProcessExecutionResults execute(String cmd, String... args) throws IOException {

		Runtime runtime = Runtime.getRuntime();

		List<String> argsList = new LinkedList<String>();
		argsList.add(cmd);
		argsList.addAll(Arrays.asList(args));
		
		Process p = runtime.exec(argsList.toArray(new String[]{}));
		
		StreamSink stdOutSink = new StreamSink(p.getInputStream());
		StreamSink stdErrSink = new StreamSink(p.getErrorStream());

		try {
			int result = p.waitFor();
			return new ProcessExecutionResults(result, stdOutSink.getData(), stdErrSink.getData());
		}
		catch (InterruptedException ex) {
			throw new IOException("Interrupted while waiting for command to complete: '" + cmd + "'");
		}
	}

}
