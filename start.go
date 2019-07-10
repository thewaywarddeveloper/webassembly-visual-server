package main

import (
	"archive/zip"
	"io"
	"io/ioutil"
	"os"
	"os/exec"
	"path/filepath"
)

func main() {
	zipPath, error := os.Executable()
	
	if error != nil {
		panic(error)
	}
	
	unzippedPath, error := ioutil.TempDir("", "")
	
	if error != nil {
		panic(error)
	}
	
	defer os.RemoveAll(unzippedPath)
	
	func () {
		zipFile, error := zip.OpenReader(zipPath)
		
		if error != nil {
			panic(error)
		}
		
		defer zipFile.Close()
		
		directoryPaths := map[string]bool{}
		
		for _, entry := range zipFile.File {
			filePath := filepath.Join(unzippedPath, entry.Name)
			
			directoryPath := filepath.Dir(filePath)
			
			if directoryPaths[directoryPath] == false {
				error := os.MkdirAll(directoryPath, 0700)
				
				if error != nil {
					panic(error)
				}
				
				directoryPaths[directoryPath] = true
			}
			
			func () {
				content, error := entry.Open()
				
				if error != nil {
					panic(error)
				}
				
				defer content.Close()
				
				func () {
					file, error := os.OpenFile(filePath, os.O_WRONLY | os.O_CREATE, entry.Mode())
					
					if error != nil {
						panic(error)
					}
					
					defer file.Close()
					
					_, error = io.Copy(file, content)
					
					if error != nil {
						panic(error)
					}
				}()
			}()
		}
	}()
	
	javaPaths := [3]string{"bin/javaw.exe", "Contents/Home/bin/java", "bin/java"}
	
	var commandPath string
	
	for _, javaPath := range javaPaths {
		path := filepath.Join(unzippedPath, "jre", javaPath)
		
		if _, error := os.Stat(path); error == nil {
			commandPath = path
			break
		}
	}
	
	jarPath := filepath.Join(unzippedPath, "webassembly-visual-server.jar")
	
	command := exec.Command(commandPath, "-jar", jarPath)
	
	command.Stdout = os.Stdout
	command.Stderr = os.Stderr
	
	command.Run()
}
