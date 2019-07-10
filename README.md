# WebAssembly Visual Server

WebAssembly modules cannot be referenced directly from HTML, instead additional
JavaScript code is required to load, compile, and instantiate them. For security
reasons, this also prevents them to be loaded directly from local disk, instead
they have to be served over HTTP with the appropriate cross-origin header.

WebAssembly Visual Server is a small, cross-platform graphical utility that
serves WebAssembly modules locally, eliminating the need to configure and run a
full-featured web server for development. It can also open a page with the
module already loaded, compiled, and instantiated, for easy access in the
console.

For an overview and precompiled binaries see [the project page][wavs].

## Development

The [source code][source] and [issues][issues] are available on GitHub.

## Compilation

To compile from source install Kotlin 1.3, JDK 11, Go 1.12, and Python 3.7, and
then run the `compile.py` script from any directory:

```
./compile.py <executable path> <jdk path> <system> <architecture>
```

Instead of specifying them on the command line, arguments can also be given as
environment variables. When both specified, command-line arguments are given
priority over environment variables. A single `-` can be used in place of a
command-line argument to only specify certain arguments on the command line.

`<executable path>` or the `WAVS_EXECUTABLE_PATH` environment variable is the
path of the generated executable. It can be a path to either a file or a
directory, in which case a default file name of `webassembly-visual-server` is
used.

`<jdk path>` or the `WAVS_JDK_PATH` environment variable is the path to the
directory containing the Java Development Kit the Java runtime packaged with the
executable will be generated from.

`<system>` or the `WAVS_SYSTEM` environment variable and `<architecture>` or the
`WAVS_ARCHITECTURE` environment variable are the target operating system and
processor architecture identifiers passed to the Go compiler.

## License

WebAssembly Visual Server is licensed under the [ISC License][license].

[wavs]:    https://thewaywarddeveloper.com/webassembly-visual-server
[source]:  https://github.com/thewaywarddeveloper/webassembly-visual-server
[issues]:  https://github.com/thewaywarddeveloper/webassembly-visual-server/issues
[license]: https://thewaywarddeveloper.com/webassembly-visual-server/license
