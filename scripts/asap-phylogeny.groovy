// always invoke this script via $ASAP_HOME/bin/groovy

/**********************
 *** Script Imports ***
**********************/


import java.nio.file.*
import groovy.util.CliBuilder
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.slf4j.LoggerFactory
import bio.comp.jlu.asap.api.DataType
import bio.comp.jlu.asap.api.FileType

import static bio.comp.jlu.asap.api.MiscConstants.*
import static bio.comp.jlu.asap.api.Paths.*




/************************
 *** Script Constants ***
************************/
final def env = System.getenv()
ASAP_HOME = env.ASAP_HOME

FAST_TREE_MP = "${ASAP_HOME}/share/fasttreemp/FastTreeMP"




/*********************
 *** Script Params ***
*********************/


log = LoggerFactory.getLogger( getClass().getName() )

def cli = new CliBuilder( usage: 'asap-phylogeny.groovy --project-path <project-path>' )
    cli.p( longOpt: 'project-path', args: 1, argName: 'project-path', required: true, 'Path to ASAÂ³P project' )
def opts = cli.parse( args )
if( !opts?.p ) {
    log.error( 'no project path provided!' )
    System.exit( 1 )
}

// log system environment vars and Java properties
log.info( "SCRIPT: ${getClass().protectionDomain.codeSource.location.path}" )
log.info( "USER: ${env.USER}" )
log.info( "CWD: ${env.PWD}" )
log.info( "HOSTNAME: ${env.HOSTNAME}" )
log.info( "ASAP_HOME: ${env.ASAP_HOME}" )
log.info( "PATH: ${env.PATH}" )
def props = System.getProperties()
log.info( "script.name: ${props['script.name']}" )
log.info( "groovy.home: ${props['groovy.home']}" )
log.info( "file.encoding: ${props['file.encoding']}" )




/********************
 *** Script Paths ***
********************/


Path rawProjectPath = Paths.get( opts.p )
if( !Files.exists( rawProjectPath ) ) {
    println( "Error: project directory (${rawProjectPath}) does not exist!" )
    System.exit(1)
}
final Path projectPath = rawProjectPath.toRealPath()
log.info( "project-path: ${projectPath}")


// read config json
Path configPath = projectPath.resolve( 'config.json' )
if( !Files.isReadable( configPath ) ) {
    log.error( 'config.json not readable!' )
    System.exit( 1 )
}
def config = (new JsonSlurper()).parseText( projectPath.resolve( 'config.json' ).toFile().text )




final Path snpDetectionPath = projectPath.resolve( PROJECT_PATH_SNPS )
final Path phylogenyPath = projectPath.resolve( PROJECT_PATH_PHYLOGENY )
Files.createFile( phylogenyPath.resolve( 'state.running' ) ) // create state.running


// create info object
def info = [
    time: [
        start: (new Date()).format( DATE_FORMAT )
    ],
    path: phylogenyPath.toString(),
    phylogeny: [
        included: [],
        excluded: []
    ]
]




/********************
 *** Script Logic ***
********************/


// merge consensus sequences of genomes and references
Path consensusPath = phylogenyPath.resolve( 'consensus.fasta' )
File consensusFile = consensusPath.toFile()

// copy first reference genome
String refName = config.references[0]
log.info( "copy ref=${refName}" )
Path referenceFastaPath = Paths.get( projectPath.toString(), PROJECT_PATH_REFERENCES, refName.substring( 0, refName.lastIndexOf( '.' ) ) + '.fasta' )
log.debug( "copy content from ref=${refName}, file=${referenceFastaPath} to consensus file=${consensusPath}" )

def p = /(^>.+[\natcgnATCGN]+)/

def m = referenceFastaPath.toFile().text =~ p  // only copy first contig as FastTree cannot handle variable sequence lengths
if( m  &&  m[0] ) consensusFile << m[0][1]

// copy genomes
config.genomes.each( { genome ->
    String genomeName = "${config.project.genus}_${genome.species}_${genome.strain}"
    def infoGenome = [
        id: genome.id,
        species: genome.species,
        strain: genome.strain
    ]
    if( Files.exists( snpDetectionPath.resolve( "${genomeName}.finished" ) )  &&  Files.exists( snpDetectionPath.resolve( "${genomeName}.consensus.fasta" ) ) ) {
        def contigs = snpDetectionPath.resolve( "${genomeName}.consensus.fasta" ).text
        m = contigs =~ p  // only copy first contig as FastTree cannot handle variable sequence lengths
        if( m  &&  m[0] ) {
            log.info( "copy genome-id=${genome.id}, genome-name=${genomeName}" )
            consensusFile << m[0][1]
            info.phylogeny.included << infoGenome
        } else {
            log.warn( "ommit genome id=${genome.id}, genome-name=${genomeName}" )
            info.phylogeny.excluded << infoGenome
        }
    } else {
        log.warn( "ommit genome id=${genome.id}, genome-name=${genomeName}" )
        info.phylogeny.excluded << infoGenome
    }
} )



// build newick file -> run fasttreeMP
Path newickPath = phylogenyPath.resolve( 'tree.nwk' )
pb = new ProcessBuilder( FAST_TREE_MP,
    '-nt',
    '-boot', '100',
    '-out', newickPath.toString(),
    consensusPath.toString() )
    .redirectErrorStream( true )
    .redirectOutput( ProcessBuilder.Redirect.INHERIT )
    .directory( phylogenyPath.toFile() )
log.info( "exec: ${pb.command()}" )
log.info( '----------------------------------------------------------------------------------------------' )
if( pb.start().waitFor() != 0 ) terminate( 'could not exec fasttreeMP!', phylogenyPath )
log.info( '----------------------------------------------------------------------------------------------' )




// store info.json
info.time.end = (new Date()).format( DATE_FORMAT )
File infoJson = phylogenyPath.resolve( 'info.json' ).toFile()
infoJson << JsonOutput.prettyPrint( JsonOutput.toJson( info ) )


// set state-file to finished
Files.move( phylogenyPath.resolve( 'state.running' ), phylogenyPath.resolve( 'state.finished' ) )




/**********************
 *** Script Methods ***
**********************/


private void terminate( String msg, Path analysisPath ) {
    terminate( msg, null, analysisPath )
}

private void terminate( String msg, Throwable t, Path analysisPath ) {

    if( t ) log.error( msg, t )
    else    log.error( msg )
    Files.move( analysisPath.resolve( 'state.running' ), analysisPath.resolve( 'state.failed' ) ) // set state-file to failed
    System.exit( 1 )

}
