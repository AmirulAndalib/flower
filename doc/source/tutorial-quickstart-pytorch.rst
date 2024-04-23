.. _quickstart-pytorch:


Quickstart PyTorch
==================

.. meta::
   :description: Check out this Federated Learning quickstart tutorial for using Flower with PyTorch to train a CNN model on MNIST.

..  youtube:: jOmmuzMIQ4c
   :width: 100%

In this tutorial we will learn how to train a Convolutional Neural Network on CIFAR10 using Flower and PyTorch.

First of all, it is recommended to create a virtual environment and run everything within a :doc:`virtualenv <contributor-how-to-set-up-a-virtual-env>`.

Our example consists of one *server* and two *clients* all having the same model.

*Clients* are responsible for generating individual weight-updates for the model based on their local datasets.
These updates are then sent to the *server* which will aggregate them to produce a better model. Finally, the *server* sends this improved version of the model back to each *client*.
A complete cycle of weight updates is called a *round*.

Now that we have a rough idea of what is going on, let's get started. We first need to install Flower. You can do this by running :

.. code-block:: shell

  $ pip install flwr

Since we want to use PyTorch to solve a computer vision task, let's go ahead and install PyTorch and the **torchvision** library:

.. code-block:: shell

  $ pip install torch torchvision


Flower Client
-------------

Now that we have all our dependencies installed, let's run a simple distributed training with two clients and one server. Our training procedure and network architecture are based on PyTorch's `Deep Learning with PyTorch <https://pytorch.org/tutorials/beginner/blitz/cifar10_tutorial.html>`_.

In a file called :code:`client.py`, import Flower and PyTorch related packages:

.. code-block:: python

    from collections import OrderedDict

    import torch
    import torch.nn as nn
    import torch.nn.functional as F
    import torchvision.transforms as transforms
    from torch.utils.data import DataLoader
    from torchvision.datasets import CIFAR10

    import flwr as fl

In addition, we define the device allocation in PyTorch with:

.. code-block:: python

    DEVICE = torch.device("cuda:0" if torch.cuda.is_available() else "cpu")

We use PyTorch to load CIFAR10, a popular colored image classification dataset for machine learning. The PyTorch :code:`DataLoader()` downloads the training and test data that are then normalized.

.. code-block:: python

    def load_data():
        """Load CIFAR-10 (training and test set)."""
        transform = transforms.Compose(
        [transforms.ToTensor(), transforms.Normalize((0.5, 0.5, 0.5), (0.5, 0.5, 0.5))]
        )
        trainset = CIFAR10(".", train=True, download=True, transform=transform)
        testset = CIFAR10(".", train=False, download=True, transform=transform)
        trainloader = DataLoader(trainset, batch_size=32, shuffle=True)
        testloader = DataLoader(testset, batch_size=32)
        num_examples = {"trainset" : len(trainset), "testset" : len(testset)}
        return trainloader, testloader, num_examples

Define the loss and optimizer with PyTorch. The training of the dataset is done by looping over the dataset, measure the corresponding loss and optimize it.

.. code-block:: python

    def train(net, trainloader, epochs):
        """Train the network on the training set."""
        criterion = torch.nn.CrossEntropyLoss()
        optimizer = torch.optim.SGD(net.parameters(), lr=0.001, momentum=0.9)
        for _ in range(epochs):
            for images, labels in trainloader:
                images, labels = images.to(DEVICE), labels.to(DEVICE)
                optimizer.zero_grad()
                loss = criterion(net(images), labels)
                loss.backward()
                optimizer.step()

Define then the validation of the  machine learning network. We loop over the test set and measure the loss and accuracy of the test set.

.. code-block:: python

    def test(net, testloader):
        """Validate the network on the entire test set."""
        criterion = torch.nn.CrossEntropyLoss()
        correct, total, loss = 0, 0, 0.0
        with torch.no_grad():
            for data in testloader:
                images, labels = data[0].to(DEVICE), data[1].to(DEVICE)
                outputs = net(images)
                loss += criterion(outputs, labels).item()
                _, predicted = torch.max(outputs.data, 1)
                total += labels.size(0)
                correct += (predicted == labels).sum().item()
        accuracy = correct / total
        return loss, accuracy

After defining the training and testing of a PyTorch machine learning model, we use the functions for the Flower clients.

The Flower clients will use a simple CNN adapted from 'PyTorch: A 60 Minute Blitz':

.. code-block:: python

    class Net(nn.Module):
        def __init__(self) -> None:
            super(Net, self).__init__()
            self.conv1 = nn.Conv2d(3, 6, 5)
            self.pool = nn.MaxPool2d(2, 2)
            self.conv2 = nn.Conv2d(6, 16, 5)
            self.fc1 = nn.Linear(16 * 5 * 5, 120)
            self.fc2 = nn.Linear(120, 84)
            self.fc3 = nn.Linear(84, 10)

        def forward(self, x: torch.Tensor) -> torch.Tensor:
            x = self.pool(F.relu(self.conv1(x)))
            x = self.pool(F.relu(self.conv2(x)))
            x = x.view(-1, 16 * 5 * 5)
            x = F.relu(self.fc1(x))
            x = F.relu(self.fc2(x))
            x = self.fc3(x)
            return x

    # Load model and data
    net = Net().to(DEVICE)
    trainloader, testloader, num_examples = load_data()

After loading the data set with :code:`load_data()` we define the Flower interface.

The Flower server interacts with clients through an interface called
:code:`Client`. When the server selects a particular client for training, it
sends training instructions over the network via the :code:`SuperLink`.
The :code:`SuperNode` pulls the server instructions from the :code:`SuperLink` and
launches a client with those instructions. The client then calls one of the
:code:`Client` methods to run your code
(i.e., to train the neural network we defined earlier).

Flower provides a convenience class called :code:`NumPyClient` which makes it
easier to implement the :code:`Client` interface when your workload uses PyTorch.
Implementing :code:`NumPyClient` usually means defining the following methods
(:code:`set_parameters` is optional though):

#. :code:`get_parameters`
    * return the model weight as a list of NumPy ndarrays
#. :code:`set_parameters` (optional)
    * update the local model weights with the parameters received from the server
#. :code:`fit`
    * set the local model weights
    * train the local model
    * receive the updated local model weights
#. :code:`evaluate`
    * test the local model

which can be implemented in the following way:

.. code-block:: python

    class CifarClient(fl.client.NumPyClient):
        def get_parameters(self, config):
            return [val.cpu().numpy() for _, val in net.state_dict().items()]

        def set_parameters(self, parameters):
            params_dict = zip(net.state_dict().keys(), parameters)
            state_dict = OrderedDict({k: torch.tensor(v) for k, v in params_dict})
            net.load_state_dict(state_dict, strict=True)

        def fit(self, parameters, config):
            self.set_parameters(parameters)
            train(net, trainloader, epochs=1)
            return self.get_parameters(config={}), num_examples["trainset"], {}

        def evaluate(self, parameters, config):
            self.set_parameters(parameters)
            loss, accuracy = test(net, testloader)
            return float(loss), num_examples["testset"], {"accuracy": float(accuracy)}

We can now create a client function to return instances of :code:`CifarClient` when called

.. code-block:: python

    def client_fn(cid: str):
        return CifarClient().to_client()

and create a :code:`ClientApp()` object using the client function

.. code-block:: python

    app = ClientApp(client_fn=client_fn)

Now, we can launch the :code:`ClientApp` object in CLI in one line:

.. code-block:: bash

    flower-client-app client:app --insecure

On this line, we launch the :code:`app` object in the :code:`client.py` module using the :code:`flower-client-app` command. Note that the :code:`--insecure` parameter is for prototyping only.

That's it for the client. We only have to implement :code:`Client` or :code:`NumPyClient`, wrap the :code:`ClientApp` around the client function, and call :code:`flower-client-app` in CLI. If you implement a client of type :code:`NumPyClient` you'll need to first call its :code:`to_client()` method.

.. 
    The string :code:`"[::]:8080"` tells the client which server to connect to. In our case we can run the server and the client on the same machine, therefore we use
    :code:`"[::]:8080"`. If we run a truly federated workload with the server and
    clients running on different machines, all that needs to change is the
    :code:`server_address` we point the client at.

Flower Server
-------------

For simple workloads we can create a Flower :code:`ServerApp` object and leave all the
configuration possibilities at their default values. In a file named
:code:`server.py`, import Flower and create a :code:`ServerApp`:

.. code-block:: python

    from flwr.server import ServerApp

    app = ServerApp()

Train the model, federated!
---------------------------

With both :code:`ClientApp`s and :code:`ServerApp` ready, we can now run everything and see federated
learning in action. First, we start the infrastructure which consists of the `SuperLink` and `SuperNode`s.
For further explaination about the infrastructure, refer to XXX.

.. code-block:: shell

    $ flower-superlink --insecure

FL systems usually have a server and multiple clients. We therefore start `SuperNode`s for each of the client. Open a new terminal and start the first `SuperNode`:

.. code-block:: shell

    $ flower-client-app client:app --insecure

Open another terminal and start the second `SuperNode`:

.. code-block:: shell

    $ flower-client-app client:app --insecure

Finally, in another terminal window, run the `ServerApp` (this starts the actual training run):

.. code-block:: shell

    $ flower-server-app server:app --insecure

Each client will have its own dataset.
You should now see how the training does in the last terminal (the one that started the :code:`ServerApp`):

.. code-block:: shell

    WARNING :   Option `--insecure` was set. Starting insecure HTTP client connected to 0.0.0.0:9091.
    INFO :      Starting Flower ServerApp, config: num_rounds=1, no round_timeout
    INFO :
    INFO :      [INIT]
    INFO :      Requesting initial parameters from one random client
    INFO :      Received initial parameters from one random client
    INFO :      Evaluating initial global parameters
    INFO :
    INFO :      [ROUND 1]
    INFO :      configure_fit: strategy sampled 2 clients (out of 2)
    INFO :      aggregate_fit: received 2 results and 0 failures
    WARNING :   No fit_metrics_aggregation_fn provided
    INFO :      configure_evaluate: strategy sampled 2 clients (out of 2)
    INFO :      aggregate_evaluate: received 2 results and 0 failures
    WARNING :   No evaluate_metrics_aggregation_fn provided
    INFO :
    INFO :      [SUMMARY]
    INFO :      Run finished 1 rounds in 15.08s
    INFO :      History (loss, distributed):
    INFO :          '\tround 1: 241.32427430152893\n'
    INFO :

Congratulations!
You've successfully built and run your first federated learning system.
The full `source code <https://github.com/adap/flower/blob/main/examples/quickstart-pytorch/client.py>`_ for this example can be found in :code:`examples/quickstart-pytorch`.
