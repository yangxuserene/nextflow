/*
 * Copyright (c) 2012, the authors.
 *
 *   This file is part of 'Nextflow'.
 *
 *   Nextflow is free software: you can redistribute it and/or modify
 *   it under the terms of the GNU General Public License as published by
 *   the Free Software Foundation, either version 3 of the License, or
 *   (at your option) any later version.
 *
 *   Nextflow is distributed in the hope that it will be useful,
 *   but WITHOUT ANY WARRANTY; without even the implied warranty of
 *   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *   GNU General Public License for more details.
 *
 *   You should have received a copy of the GNU General Public License
 *   along with Nextflow.  If not, see <http://www.gnu.org/licenses/>.
 */

package nextflow
import java.nio.file.Path

import groovy.io.FileType
import groovy.util.logging.Slf4j
import groovyx.gpars.dataflow.DataflowBroadcast
import groovyx.gpars.dataflow.DataflowQueue
import groovyx.gpars.dataflow.DataflowReadChannel
import groovyx.gpars.dataflow.DataflowVariable
import groovyx.gpars.dataflow.operator.PoisonPill
import nextflow.util.FileHelper
/**
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
@Slf4j
class Nextflow {

    static registerTypes() {

        String.metaClass.define {
            oldAsType = String.metaClass.getMetaMethod("asType", [Class] as Class[])
            asType = { Class clazz ->
                if (clazz == Path) {
                    FileHelper.asPath((String)delegate)
                }
                else {
                    oldAsType.invoke(delegate, clazz)
                }
            }
        }

        GString.metaClass.define {
            oldAsType = String.metaClass.getMetaMethod("asType", [Class] as Class[])
            asType = { Class clazz ->
                if (clazz == Path) {
                    FileHelper.asPath((String)delegate)
                }
                else {
                    oldAsType.invoke(delegate, clazz)
                }
            }
        }

    }


    /**
     * Read a value from the specified channel
     *
     * @param channel
     * @return
     */
    static def <T> T read( def channel ) {
        assert channel

        if ( channel instanceof DataflowBroadcast ) {
            log.debug 'Read DataflowBroadcast channel'
            channel.createReadChannel().getVal()
        }
        else if ( channel instanceof DataflowReadChannel ) {
            log.debug 'Read DataflowReadChannel channel'
            channel.getVal()
        }
        else {
            log.warn "The value is not channel '$channel' (${channel?.class?.simpleName})"
            return channel
        }
    }


    /**
     * Create a {@code DataflowVariable} binding it to the specified value
     *
     * @param value
     * @return
     */
    static <T> DataflowVariable<T> val( T value = null ) {
        def result = new DataflowVariable<T>()
        if( value ) {
            result.bind(value)
        }
        result
    }

    /**
     * Create a {@code DataflowQueue} populating with the specified values
     * <p>
     * This 'queue' data structure can be viewed as a point-to-point (1 to 1, many to 1) communication channel.
     * It allows one or more producers send messages to one reader.
     *
     * @param values
     * @return
     */
    static <T> DataflowQueue<T> channel( Collection<T> values = null ) {
        log.trace("channel[1]: $values")

        def channel = new DataflowQueue<T>()
        if ( values )  {
            // bind e
            values.each { channel << it }

            // since queue is 'finite' close it by a poison pill
            // so the operator will stop on when all values in the queue are consumed
            // (otherwise it will wait forever for a new entry)
            channel << PoisonPill.instance
        }

        return channel
    }

    /**
     * Create a {@code DataflowQueue} populating with a single value
     * <p>
     * This 'queue' data structure can be viewed as a point-to-point (1 to 1, many to 1) communication channel.
     * It allows one or more producers send messages to one reader.
     *
     * @param item
     * @return
     */
    static <T> DataflowQueue<T> channel( T... items ) {
        log.trace("channel[2]: $items")
        return channel(items as List)
    }

    /**
     * Create as thread-safe list buffers for message transfer among concurrent tasks or threads.
     * <p>The underlying data structure is a {@code DataflowBroadcast} which offers a publish-subscribe
     * (1 to many, many to many) communication model. One or more producers write messages,
     * while all registered readers will receive all the messages.
     *
     * @param item
     * @return
     */
    @Deprecated
    static <T> DataflowBroadcast<T> list( T item ) {
        list([item])
    }


    /**
     * Create as thread-safe list buffers for message transfer among concurrent tasks or threads.
     * <p>The underlying data structure is a {@code DataflowBroadcast} which offers a publish-subscribe
     * (1 to many, many to many) communication model. One or more producers write messages,
     * while all registered readers will receive all the messages.
     *
     * @param item
     * @return
     */
    @Deprecated
    static <T> DataflowBroadcast<T> list( Collection<T> values = null ) {
        def result = new DataflowBroadcast()
        if ( values )  {
            values.each { result << it }
            result << PoisonPill.instance
        }
        return result
    }

    /**
     * File factory utility method.
     *
     * @param name
     * @return
     */
    static def fileNamePattern( def name ) {

        if( !name ) return null

        /*
         * expand special user home '~' character
         */
        def sName = name.toString()
        if( sName == '~' ) {
            sName = System.getProperty('user.home')
        }
        else if( sName.startsWith('~'+File.separatorChar) ) {
            sName = sName.replace('~', System.getProperty('user.home'))
        }

        /*
         * split the parent path from the file name
         */
        final path = FileHelper.asPath(sName).normalize().toAbsolutePath()
        def base = path.getParent()
        def filePattern = path.getFileName().toString()

        /*
         * punctual file, just return it
         */
        if( !filePattern.contains('*') && !filePattern.contains('?') ) {
            return path
        }

        /*
         * when the name contains a wildcard character, it returns the list of
         * all matching files (eventually empty)
         *
         * TODO use newDirectoryStream here and glob eventually
         */
        filePattern = filePattern.replace("?", ".?").replace("*", ".*")
        def result = new LinkedList()
        base.eachFileMatch(FileType.FILES, ~/$filePattern/ ) { result << it }
        return result

    }

    static file( def file ) {
        assert file

        switch (file) {
            case Path:
                return ((Path) file).normalize().toAbsolutePath()
                break;

            case File:
                return ((File) file).toPath().normalize().toAbsolutePath()
                break;

            default:
                return fileNamePattern(file?.toString())
        }

    }

}
