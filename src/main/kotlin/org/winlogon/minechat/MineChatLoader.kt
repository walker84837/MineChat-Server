package org.winlogon.minechat

import io.papermc.paper.plugin.loader.PluginClasspathBuilder
import io.papermc.paper.plugin.loader.PluginLoader
import io.papermc.paper.plugin.loader.library.impl.MavenLibraryResolver
import org.eclipse.aether.artifact.DefaultArtifact
import org.eclipse.aether.graph.Dependency
import org.eclipse.aether.repository.RemoteRepository

import java.nio.file.Path

class MineChatPluginLoader : PluginLoader {
    override fun classloader(classpathBuilder: PluginClasspathBuilder) {
	val caffeineVersion = "3.2.0";
        val resolver = MavenLibraryResolver().apply {
            addDependency(Dependency(DefaultArtifact("com.github.ben-manes.caffeine:caffeine:$caffeineVersion"), null))
            addRepository(
                RemoteRepository.Builder("maven-central", "default", "https://repo1.maven.org/maven2/")
                    .build()
            )
        }
        classpathBuilder.addLibrary(resolver)
    }
}
