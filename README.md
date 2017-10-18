# jobcoin
Take-home assignment from Gemini.com

## How to run:
1. Clone the repo.
2. First run the server (`chmod +x` if it's not executable) `run_server.sh`
3. In a separate terminal window, run the cli `run_cli.sh`
4. Follow the instructions on screen.

## How to review this code:
The main entry point to the server is `Jobcoin.scala`. 
That code Instantiates all the relevant objects and wires everything together. It also creates and runs a lightweight HTTP server.
Then I recommend this order: AddressManager, AddressGenerator, Tumbler, Mixer, JobcoinNetwork, NetworkMonitor, TransactionScheduler, everything else.

## Call diagram (not my best work :))

![ugly call diagram I whipped up](jobcoin.uml.png?raw=true)

Comments and notes:
-----------------------------
1. Separation of systems
   Conceptually, I've designed the implementation with the idea that there are a few available network services,
   each of which is in charge of a specific capability.

   The services are as follows:
     - JobcoinNetwork: The implementation of the REST API for jobcoin.gemini.com
     - AddressManager: Basically a storage layer, handles any required persistence related to addresses.
     - Mixer: The core service running the mixing operations. It's the entry point for registering target addresses
       and schedules the transactions after mixing.
     - NetworkMonitor: Implements monitoring of the Jobcoin network. This is the service a mixer can use to monitor
       addresses, and this service notifies watchers in case a monitor triggers.
     - TransactionScheduler: A service for scheduling transactions

   For the simplicity of this project, all of these are running in the memory of the server.
   Though using the same design, they could be independent services.

2. "Network calls":
   In a real micro-services environment, there would be underlying infrastructure that takes care of resiliency
   and performance. The code "assumes" these exist (retries, enqueuing, etc)

   I've made the following assumptions:
   - When making a Future[Unit] call: I assume an enqueueing infrastructure, and only a failure to **enqueue** returns
   an exception.
   - When making a Future[T] call: I assume a direct call to the service. Failure there means the service failed
   handling the request.
   - When implementing a Future[Unit] method: returning a Future.exception assumes to retry the entire method
   - When implementing a Future[T] method: returning a Future.exception assumes to return the exception to the calling service.


3. About open source: I've used mostly Twitter open source code due to my familiarity with it.
   You'll find most of it is self-explanatory.

4. There's relevant documentation in the code itself.

5. I didn't write unit tests, didn't know if you expected any. If you did expect them, let me know asap and I'll write some. Thanks!
   
   (fwiw, I did have some local scripts I wrote to test, you can see one by running `test` in the cli, but didn't fiddle around with integrating various unit
   testing frameworks, e.g. scalatest.)