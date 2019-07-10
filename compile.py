#!/usr/bin/env python3.7

import os
import os.path
import pathlib
import subprocess
import sys
import tempfile
import zipfile

source_path = pathlib.Path(sys.argv[0]).parent

def argument(index, variable):
    if len(sys.argv) > index and sys.argv[index] != '-':
        return sys.argv[index]
    
    else:
        return os.environ[variable]

target_path  = argument(1, 'WAVS_EXECUTABLE_PATH')
jdk_path     = argument(2, 'WAVS_JDK_PATH')
system       = argument(3, 'WAVS_SYSTEM')
architecture = argument(4, 'WAVS_ARCHITECTURE')

filename = 'webassembly-visual-server'

if (os.path.isdir(target_path)):
    target_path = os.path.join(target_path, filename)

def run(command, environment={}):
    try:
        subprocess.run(command, env=dict(os.environ, **environment), check=True)
    
    except subprocess.CalledProcessError:
        sys.exit(1)

with tempfile.TemporaryDirectory() as directory_path:
    if system == 'windows':
        options = ["-ldflags", "-H windowsgui"]
    
    else:
        options = []
    
    run(['go', 'build', *options, '-a', '-o', target_path, source_path/'start.go'], {'GOARCH': architecture, 'GOOS': system})
    
    module_path = os.path.join(jdk_path, 'jmods')
    
    if not os.path.isdir(module_path):
        module_path = os.path.join(jdk_path, 'Contents/Home/jmods')
    
    jre_path = os.path.join(directory_path, 'jre')
    
    run(['jlink', '--strip-debug', '--no-header-files', '--no-man-pages', '--dedup-legal-notices=error-if-not-same-content',
        '-p', module_path, '--add-modules', 'java.base,java.desktop,java.net.http,jdk.httpserver', '--output', jre_path])
    
    with zipfile.ZipFile(target_path, 'a') as zip_file:
        jre_file_paths = []
        
        for jre_directory_path, _, jre_file_names in os.walk(jre_path):
            for jre_file_name in jre_file_names:
                jre_file_paths.append(os.path.join(jre_directory_path, jre_file_name))
        
        for jre_file_path in sorted(jre_file_paths):
            jre_file_inner_path = os.path.join('jre', str(pathlib.Path(jre_file_path).relative_to(jre_path)))
            
            zip_file.write(jre_file_path, jre_file_inner_path, zipfile.ZIP_DEFLATED)
        
        jar_path = os.path.join(directory_path, f'{filename}.jar')
        
        run(['kotlinc', source_path/'application.kt', '-jvm-target', '11', '-include-runtime', '-d', jar_path])
        
        with zipfile.ZipFile(jar_path, 'a') as jar_file:
            jar_file.write(source_path/'icon.png', 'com/thewaywarddeveloper/wavs/icon.png')
        
        zip_file.write(jar_path, f'{filename}.jar')
